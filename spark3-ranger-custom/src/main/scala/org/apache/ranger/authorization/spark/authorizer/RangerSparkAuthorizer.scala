package org.apache.ranger.authorization.spark.authorizer

import java.util.{List => JList}

import org.apache.commons.lang.StringUtils
import org.apache.commons.logging.LogFactory
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.permission.FsAction
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.hive.common.FileUtils
import org.apache.hadoop.hive.ql.security.authorization.plugin._
import org.apache.ranger.authorization.spark.authorizer.SparkAccessType.SparkAccessType
import org.apache.ranger.authorization.spark.authorizer.SparkObjectType.SparkObjectType
import org.apache.ranger.authorization.spark.authorizer.SparkOperationType.SparkOperationType
import org.apache.ranger.authorization.utils.StringUtil
import org.apache.ranger.plugin.policyengine.RangerAccessRequest
import org.apache.ranger.plugin.util.RangerPerfTracer
import org.apache.spark.sql.{SparkSession, UserAuthUtils}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

object RangerSparkAuthorizer {
  private val LOG = LogFactory.getLog(this.getClass.getSimpleName.stripSuffix("$"))

  private def sparkPlugin = RangerSparkPlugin.build().getOrCreate()

  def checkPrivileges(
                       spark: SparkSession,
                       opType: SparkOperationType,
                       inputs: Seq[SparkPrivilegeObject],
                       outputs: Seq[SparkPrivilegeObject]): Unit = {
    val (user, groups) = UserAuthUtils.getUserAuth(spark)

    LOG.info(
      s"""
         |+=======================================+
         ||RangerSparkAuthorizer Check Privileges |
         ||---------------------------------------|
         ||Checking privileges, with user $user, groups $groups
         ||---------------------------------------|
         ||RangerSparkAuthorizer Check Privileges |
         |+=======================================+
               """.stripMargin)

    val auditHandler = new RangerSparkAuditHandler
    val perf = if (RangerPerfTracer.isPerfTraceEnabled(PERF_SPARKAUTH_REQUEST_LOG)) {
      RangerPerfTracer.getPerfTracer(PERF_SPARKAUTH_REQUEST_LOG,
        "RangerSparkAuthorizer.checkPrivileges()")
    } else {
      null
    }
    try {
      // build plan tree with full op
      val requests = new ArrayBuffer[RangerSparkAccessRequest]()
      if (inputs.isEmpty && opType == SparkOperationType.SHOWDATABASES) {
        val resource = new RangerSparkResource(SparkObjectType.DATABASE, None)
        requests += new RangerSparkAccessRequest(resource, user, groups, opType.toString,
          SparkAccessType.USE, sparkPlugin.getClusterName, spark.conf.get("spark.sql.proxy-user.clientIp", "Error"))
      }

      // get access request from every tree node
      def addAccessRequest(objs: Seq[SparkPrivilegeObject], isInput: Boolean): Unit = {
        objs.foreach { obj =>
          val resource = getSparkResource(obj, opType)
          if (resource != null) {
            val objectName = obj.getObjectName
            val objectType = resource.getObjectType
            if (objectType == SparkObjectType.URI && isPathInFSScheme(objectName)) {
              val fsAction = getURIAccessType(opType)
              val hadoopConf = spark.sparkContext.hadoopConfiguration
              if (!canAccessURI(user, fsAction, objectName, hadoopConf)) {
                throw new HiveAccessControlException(s"Permission denied: user [$user] does not" +
                  s" have [${fsAction.name}] privilege on [$objectName]")
              }
            } else {
              val accessType = getAccessType(obj, opType, objectType, isInput)
              if (accessType != SparkAccessType.NONE && !requests.exists(
                o => o.getSparkAccessType == accessType && o.getResource == resource)) {
                requests += new RangerSparkAccessRequest(resource, user, groups, opType.toString,
                  accessType, sparkPlugin.getClusterName, spark.conf.get("spark.sql.proxy-user.clientIp", "Error"))
              }
            }
          }
        }
      }

      addAccessRequest(inputs, isInput = true)
      addAccessRequest(outputs, isInput = false)
      requests.foreach { request =>
        val resource = request.getResource.asInstanceOf[RangerSparkResource]
        if (resource.getObjectType == SparkObjectType.COLUMN &&
          StringUtils.contains(resource.getColumn, ",")) {
          resource.setServiceDef(sparkPlugin.getServiceDef)
          val colReqs: JList[RangerAccessRequest] = resource.getColumn.split(",")
            .filter(StringUtils.isNotBlank).map { c =>
            val colRes = new RangerSparkResource(SparkObjectType.COLUMN,
              Option(resource.getDatabase), resource.getTable, c)
            val colReq = request.copy()
            colReq.setResource(colRes)
            colReq.asInstanceOf[RangerAccessRequest]
          }.toList.asJava
          //disable columns audit handler
          //val colResults = sparkPlugin.isAccessAllowed(colReqs)
          val colResults = sparkPlugin.isAccessAllowed(colReqs, auditHandler)
          if (colResults != null) {
            for (c <- colResults.asScala) {
              if (c != null && !c.getIsAllowed) {
                throw new SparkAccessControlException(s"Permission denied: user [$user] does not" +
                  s" have [${request.getSparkAccessType}] privilege on [${resource.getAsString}]")
              }
            }
          }
        } else {
          val result = sparkPlugin.isAccessAllowed(request, auditHandler)
          if (result != null && !result.getIsAllowed) {
            throw new SparkAccessControlException(s"Permission denied: user [$user] does not" +
              s" have [${request.getSparkAccessType}] privilege on [${resource.getAsString}]")
          }
        }
      }
    } finally {
      RangerPerfTracer.log(perf)
    }
  }

  def isAllowed(spark: SparkSession, obj: SparkPrivilegeObject): Boolean = {
    val (user, groups) = UserAuthUtils.getUserAuth(spark)
    createSparkResource(obj) match {
      case Some(resource) =>
        val request =
          new RangerSparkAccessRequest(resource, user, groups, sparkPlugin.getClusterName)
        val result = sparkPlugin.isAccessAllowed(request)
        if (request == null) {
          LOG.error("Internal error: null RangerAccessResult received back from isAccessAllowed")
          false
        } else if (!result.getIsAllowed) {
          if (LOG.isDebugEnabled) {
            val path = resource.getAsString
            LOG.debug(s"Permission denied: user [$user] does not have" +
              s" [${request.getSparkAccessType}] privilege on [$path]. resource[$resource]," +
              s" request[$request], result[$result]")
          }
          false
        } else {
          true
        }
      case _ =>
        LOG.error("RangerSparkResource returned by createSparkResource is null")
        false
    }

  }

  private val PERF_SPARKAUTH_REQUEST_LOG = RangerPerfTracer.getPerfLogger("sparkauth.request")

  def createSparkResource(privilegeObject: SparkPrivilegeObject): Option[RangerSparkResource] = {
    val objectName = privilegeObject.getObjectName
    val dbName = privilegeObject.getDbname
    val objectType = privilegeObject.getType
    objectType match {
      case SparkPrivilegeObjectType.DATABASE =>
        Some(RangerSparkResource(SparkObjectType.DATABASE, Option(objectName)))
      case SparkPrivilegeObjectType.TABLE_OR_VIEW =>
        Some(RangerSparkResource(SparkObjectType.TABLE, Option(dbName), objectName))
      case _ =>
        LOG.warn(s"RangerSparkAuthorizer.createSparkResource: unexpected objectType: $objectType")
        None
    }
  }

  private def getAccessType(obj: SparkPrivilegeObject, opType: SparkOperationType,
                            objectType: SparkObjectType, isInput: Boolean): SparkAccessType = {
    objectType match {
      case SparkObjectType.URI if isInput => SparkAccessType.READ
      case SparkObjectType.URI => SparkAccessType.WRITE
      case _ => obj.getActionType match {
        case SparkPrivObjectActionType.INSERT | SparkPrivObjectActionType.INSERT_OVERWRITE =>
          SparkAccessType.UPDATE
        case SparkPrivObjectActionType.OTHER =>
          import SparkOperationType._
          opType match {
            case CREATEDATABASE if obj.getType == SparkPrivilegeObjectType.DATABASE =>
              SparkAccessType.CREATE
            case CREATEFUNCTION if obj.getType == SparkPrivilegeObjectType.FUNCTION =>
              SparkAccessType.CREATE
            case CREATETABLE | CREATEVIEW | CREATETABLE_AS_SELECT
              if obj.getType == SparkPrivilegeObjectType.TABLE_OR_VIEW =>
              if (isInput) SparkAccessType.SELECT else SparkAccessType.CREATE
            case ALTERDATABASE | ALTERTABLE_ADDCOLS |
                 ALTERTABLE_ADDPARTS | ALTERTABLE_DROPPARTS |
                 ALTERTABLE_LOCATION | ALTERTABLE_PROPERTIES | ALTERTABLE_SERDEPROPERTIES |
                 ALTERVIEW_RENAME | MSCK | ALTERTABLE_RENAMECOL | ALTERTABLE_RENAMEPART |
                 ALTERTABLE_RENAME | ALTERDATABASE_LOCATION | REFRESHTABLE | REFRESHFUNCTION => SparkAccessType.ALTER
            case DROPFUNCTION | DROPTABLE | DROPVIEW | DROPDATABASE =>
              SparkAccessType.DROP
            case LOAD => if (isInput) SparkAccessType.SELECT else SparkAccessType.UPDATE
            case QUERY | SHOW_CREATETABLE | SHOWPARTITIONS |
                 SHOW_TBLPROPERTIES => SparkAccessType.SELECT
            case SHOWCOLUMNS | DESCTABLE =>
              StringUtil.toLower(RangerSparkPlugin.showColumnsOption) match {
                case "show-all" => SparkAccessType.USE
                case _ => SparkAccessType.SELECT
              }
            case SHOWDATABASES | SWITCHDATABASE | DESCDATABASE | SHOWTABLES | SHOWFUNCTIONS | SHOWVIEWS => SparkAccessType.USE
            case TRUNCATETABLE => SparkAccessType.UPDATE

            case CACHE => SparkAccessType.ADMIN // need admin to management table
            case STREAM => if (isInput) SparkAccessType.SELECT else SparkAccessType.ADMIN
            case MUTATION => if (isInput) SparkAccessType.SELECT else SparkAccessType.UPDATE
            case DATA_MAP => SparkAccessType.ADMIN // need admin to tunning job
            case MANAGEMENT => if (isInput) SparkAccessType.SELECT else SparkAccessType.UPDATE // need admin to management job
            case DESCFUNCTION => SparkAccessType.SELECT
            case _ => SparkAccessType.NONE
          }
      }
    }
  }

  private def getObjectType(
                             obj: SparkPrivilegeObject, opType: SparkOperationType): SparkObjectType = {
    obj.getType match {
      case SparkPrivilegeObjectType.DATABASE | null => SparkObjectType.DATABASE
      case SparkPrivilegeObjectType.TABLE_OR_VIEW if !StringUtil.isEmpty(obj.getColumns.asJava) =>
        SparkObjectType.COLUMN
      case SparkPrivilegeObjectType.TABLE_OR_VIEW if opType.toString.toLowerCase.contains("view") =>
        SparkObjectType.VIEW
      case SparkPrivilegeObjectType.TABLE_OR_VIEW => SparkObjectType.TABLE
      case SparkPrivilegeObjectType.FUNCTION => SparkObjectType.FUNCTION
      case SparkPrivilegeObjectType.DFS_URI => SparkObjectType.URI
      case _ => SparkObjectType.NONE
    }
  }

  private def getSparkResource(
                                obj: SparkPrivilegeObject, opType: SparkOperationType): RangerSparkResource = {
    import SparkObjectType._
    val objectType = getObjectType(obj, opType)
    val resource = objectType match {
      case DATABASE => RangerSparkResource(objectType, Option(obj.getDbname))
      case TABLE | VIEW | FUNCTION =>
        RangerSparkResource(objectType, Option(obj.getDbname), obj.getObjectName)
      case COLUMN =>
        RangerSparkResource(objectType, Option(obj.getDbname), obj.getObjectName,
          obj.getColumns.mkString(","))
      case _ => null
    }
    if (resource != null) resource.setServiceDef(sparkPlugin.getServiceDef)
    resource
  }

  private def canAccessURI(
                            user: String, action: FsAction, uri: String, conf: Configuration): Boolean = action match {
    case FsAction.NONE => true
    case _ =>
      try {
        val filePath = new Path(uri)
        val fs = FileSystem.get(filePath.toUri, conf)
        val fileStat = fs.globStatus(filePath)
        if (fileStat != null && fileStat.nonEmpty) fileStat.forall { file =>
          FileUtils.isOwnerOfFileHierarchy(fs, file, user) ||
            FileUtils.isActionPermittedForFileHierarchy(fs, file, user, action)
        } else {
          val file = FileUtils.getPathOrParentThatExists(fs, filePath)
          FileUtils.checkFileAccessWithImpersonation(fs, file, action, user)
          true
        }
      } catch {
        case e: Exception =>
          LOG.error("Error getting permissions for " + uri, e)
          false
      }
  }

  private def getURIAccessType(operationType: SparkOperationType): FsAction = {
    import SparkOperationType._

    operationType match {
      case LOAD => FsAction.READ
      case CREATEDATABASE | CREATETABLE | CREATETABLE_AS_SELECT | ALTERDATABASE |
           ALTERTABLE_ADDCOLS | ALTERTABLE_RENAMECOL | ALTERTABLE_RENAMEPART | ALTERTABLE_RENAME |
           ALTERTABLE_DROPPARTS | ALTERTABLE_ADDPARTS | ALTERTABLE_PROPERTIES |
           ALTERTABLE_SERDEPROPERTIES | ALTERTABLE_LOCATION | QUERY => FsAction.ALL
      case _ => FsAction.NONE
    }
  }

  private def isPathInFSScheme(objectName: String): Boolean = {
    objectName.nonEmpty && sparkPlugin.fsScheme.exists(objectName.startsWith)
  }
}
