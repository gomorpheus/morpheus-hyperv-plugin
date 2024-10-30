package com.morpheusdata.hyperv

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.backup.BackupExecutionProvider
import com.morpheusdata.core.backup.response.BackupExecutionResponse
import com.morpheusdata.core.backup.util.BackupResultUtility
import com.morpheusdata.core.util.DateUtility
import com.morpheusdata.hyperv.utils.HypervOptsUtility
import com.morpheusdata.model.Backup
import com.morpheusdata.model.BackupResult
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class HyperVBackupExecutionProvider implements BackupExecutionProvider {

	HyperVPlugin plugin
	MorpheusContext morpheusContext
	HyperVApiService apiService

	HyperVBackupExecutionProvider(HyperVPlugin plugin, MorpheusContext morpheusContext) {
		this.plugin = plugin
		this.morpheusContext = morpheusContext
		this.apiService = new HyperVApiService(morpheusContext)
	}

	/**
	 * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
	 * @return an implementation of the MorpheusContext for running Future based rxJava queries
	 */
	MorpheusContext getMorpheus() {
		return morpheusContext
	}

	/**
	 * Add additional configurations to a backup. Morpheus will handle all basic configuration details, this is a
	 * convenient way to add additional configuration details specific to this backup provider.
	 * @param backupModel the current backup the configurations are applied to.
	 * @param config the configuration supplied by external inputs.
	 * @param opts optional parameters used for configuration.
	 * @return a {@link ServiceResponse} object. A ServiceResponse with a false success will indicate a failed
	 * configuration and will halt the backup creation process.
	 */
	@Override
	ServiceResponse configureBackup(Backup backup, Map config, Map opts) {
		return ServiceResponse.success(backup)
	}

	/**
	 * Validate the configuration of the backup. Morpheus will validate the backup based on the supplied option type
	 * configurations such as required fields. Use this to either override the validation results supplied by the
	 * default validation or to create additional validations beyond the capabilities of option type validation.
	 * @param backupModel the backup to validate
	 * @param config the original configuration supplied by external inputs.
	 * @param opts optional parameters used for
	 * @return a {@link ServiceResponse} object. The errors field of the ServiceResponse is used to send validation
	 * results back to the interface in the format of {@code errors['fieldName'] = 'validation message' }. The msg
	 * property can be used to send generic validation text that is not related to a specific field on the model.
	 * A ServiceResponse with any items in the errors list or a success value of 'false' will halt the backup creation
	 * process.
	 */
	@Override
	ServiceResponse validateBackup(Backup backup, Map config, Map opts) {
		return ServiceResponse.success(backup)
	}

	/**
	 * Create the backup resources on the external provider system.
	 * @param backupModel the fully configured and validated backup
	 * @param opts additional options used during backup creation
	 * @return a {@link ServiceResponse} object. A ServiceResponse with a success value of 'false' will indicate the
	 * creation on the external system failed and will halt any further backup creation processes in morpheus.
	 */
	@Override
	ServiceResponse createBackup(Backup backup, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Delete the backup resources on the external provider system.
	 * @param backupModel the backup details
	 * @param opts additional options used during the backup deletion process
	 * @return a {@link ServiceResponse} indicating the results of the deletion on the external provider system.
	 * A ServiceResponse object with a success value of 'false' will halt the deletion process and the local refernce
	 * will be retained.
	 */
	@Override
	ServiceResponse deleteBackup(Backup backup, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Delete the results of a backup execution on the external provider system.
	 * @param backupResultModel the backup results details
	 * @param opts additional options used during the backup result deletion process
	 * @return a {@link ServiceResponse} indicating the results of the deletion on the external provider system.
	 * A ServiceResponse object with a success value of 'false' will halt the deletion process and the local refernce
	 * will be retained.
	 */
	@Override
	ServiceResponse deleteBackupResult(BackupResult backupResult, Map opts) {
		log.debug("Delete backup result {}", backupResult.id)

		def rtn = [success:true]
		try {
			def config = backupResult.getConfigMap()
			def snapshotId = config.snapshotId
			if(snapshotId) {
				def server
				def cloudId = config.cloudId
				def hypervisorId = config.hypervisorId
				if (backupResult.serverId) {
					server = morpheusContext.services.computeServer.get(backupResult.serverId)
				} else {
					def container = morpheusContext.services.workload.get(backupResult.containerId)
					server = morpheusContext.services.computeServer.get(container.serverId)
				}
				opts = getAllHypervOptsForServer(server, cloudId, hypervisorId)
				def serverExternalId = server?.externalId
				def result
				if (serverExternalId) {
					result = apiService.deleteSnapshot(opts, serverExternalId, snapshotId)
				} else {
					result = [success: true]
				}
				if (!result.success) {
					rtn.success = false
				}
			}
		} catch(e) {
			log.error("error in deleteBackupResult: {}", e,e)
			rtn.success = false
		}
		return ServiceResponse.create(rtn)
	}

	def getAllHypervOptsForServer(server, cloudId=null, hypervisorId=null) {
		def cloud
		def parentServer
		if(server) {
			cloud = server.cloud
			parentServer = server.parentServer
		} else if(cloudId && hypervisorId) {
			cloud = morpheusContext.services.computeServer.get(cloudId?.toLong())
			parentServer = morpheusContext.services.computeServer.get(hypervisorId?.toLong())
		}
		def rtn = HypervOptsUtility.getHypervZoneOpts(morpheusContext, cloud)
		rtn += HypervOptsUtility.getHypervHypervisorOpts(parentServer)
		return rtn
	}

	/**
	 * A hook into the execution process. This method is called before the backup execution occurs.
	 * @param backupModel the backup details associated with the backup execution
	 * @param opts additional options used during the backup execution process
	 * @return a {@link ServiceResponse} indicating the success or failure of the execution preperation. A success value
	 * of 'false' will halt the execution process.
	 */
	@Override
	ServiceResponse prepareExecuteBackup(Backup backup, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Provide additional configuration on the backup result. The backup result is a representation of the output of
	 * the backup execution including the status and a reference to the output that can be used in any future operations.
	 * @param backupResultModel
	 * @param opts
	 * @return a {@link ServiceResponse} indicating the success or failure of the method. A success value
	 * of 'false' will halt the further execution process.
	 */
	@Override
	ServiceResponse prepareBackupResult(BackupResult backupResultModel, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Initiate the backup process on the external provider system.
	 * @param backup the backup details associated with the backup execution.
	 * @param backupResult the details associated with the results of the backup execution.
	 * @param executionConfig original configuration supplied for the backup execution.
	 * @param cloud cloud context of the target of the backup execution
	 * @param computeServer the target of the backup execution
	 * @param opts additional options used during the backup execution process
	 * @return a {@link ServiceResponse} indicating the success or failure of the backup execution. A success value
	 * of 'false' will halt the execution process.
	 */
	@Override
	ServiceResponse<BackupExecutionResponse> executeBackup(Backup backup, BackupResult backupResult, Map executionConfig, Cloud cloud, ComputeServer computeServer, Map opts) {
		log.debug("executeBackup: executionConfig: {}, opts: {}", executionConfig, opts)
		ServiceResponse<BackupExecutionResponse> rtn = ServiceResponse.prepare(new BackupExecutionResponse(backupResult))
		try {
			def container = morpheusContext.services.workload.get(executionConfig.containerId)
			def snapshotName = "${computeServer.externalId}.${System.currentTimeMillis()}".toString()
			def outputPath = executionConfig.workingPath
			log.debug("outputPath: ${outputPath}")
			rtn.data.backupResult.status = BackupResult.Status.IN_PROGRESS
			def hypervOpts = HypervOptsUtility.getAllHypervWorloadOpts(morpheusContext, container)
			def hypervisorId = computeServer?.parentServer?.id
			hypervOpts.snapshotId = snapshotName
			def vmId = computeServer.externalId
			def snapshotResults = apiService.snapshotServer(hypervOpts, vmId)
			log.debug("backup complete: {}", snapshotResults)
			if (snapshotResults.success) {
				rtn.data.backupResult.backupSetId = opts.backupSetId
				rtn.data.backupResult.executorIpAddress = executionConfig.ipAddress
				rtn.data.backupResult.resultBase = 'hyperv'
				rtn.data.backupResult.resultBucket = snapshotResults.snapshotId
				rtn.data.backupResult.resultPath = snapshotResults.snapshotId
				rtn.data.backupResult.resultArchive = snapshotResults.snapshotId
				rtn.data.backupResult.sizeInMb = 0l
				rtn.data.backupResult.snapshotId = snapshotResults.snapshotId
				rtn.data.backupResult.setConfigProperty("snapshotId", snapshotResults.snapshotId)
				rtn.data.backupResult.setConfigProperty("vmId", vmId)
				rtn.data.backupResult.setConfigProperty("zoneId", cloud.id)
				rtn.data.backupResult.setConfigProperty("hypervisorId", hypervisorId)
				rtn.data.updates = true
				rtn.data.backupResult.status = BackupResult.Status.SUCCEEDED
				if (!backupResult.endDate) {
					rtn.data.backupResult.endDate = new Date()
					def startDate = backupResult.startDate
					if (startDate) {
						def start = DateUtility.parseDate(startDate)
						def end = rtn.data.backupResult.endDate
						rtn.data.backupResult.durationMillis = end.time - start.time
					}
				}
			} else {
				//error
				rtn.data.backupResult.backupSetId = opts.backupSetId
				rtn.data.backupResult.executorIpAddress = executionConfig.ipAddress
				rtn.data.backupResult.sizeInMb = 0l
				rtn.data.backupResult.status = BackupResult.Status.FAILED
				rtn.data.backupResult.errorOutput = snapshotResults.error?.toString().encodeAsBase64()
				rtn.data.updates = true
			}
			rtn.success = true
		} catch (e) {
			log.error("executeBackup: ${e}", e)
			rtn.msg = e.getMessage()
			def error = "Failed to execute backup"
			rtn.data.backupResult.backupSetId = executionConfig.backupResultId ?: BackupResultUtility.generateBackupResultSetId()
			rtn.data.backupResult.executorIpAddress = executionConfig.ipAddress
			rtn.data.backupResult.sizeInMb = 0l
			rtn.data.backupResult.status = BackupResult.Status.FAILED
			rtn.data.backupResult.errorOutput = error.encodeAsBase64()
			rtn.data.updates = true
		}
		return rtn
	}

	/**
	 * Periodically call until the backup execution has successfully completed. The default refresh interval is 60 seconds.
	 * @param backupResult the reference to the results of the backup execution including the last known status. Set the
	 *                     status to a canceled/succeeded/failed value from one of the {@link BackupStatusUtility} values
	 *                     to end the execution process.
	 * @return a {@link ServiceResponse} indicating the success or failure of the method. A success value
	 * of 'false' will halt the further execution process.n
	 */
	@Override
	ServiceResponse<BackupExecutionResponse> refreshBackupResult(BackupResult backupResult) {
		return ServiceResponse.success(new BackupExecutionResponse(backupResult))
	}

	/**
	 * Cancel the backup execution process without waiting for a result.
	 * @param backupResultModel the details associated with the results of the backup execution.
	 * @param opts additional options.
	 * @return a {@link ServiceResponse} indicating the success or failure of the backup execution cancellation.
	 */
	@Override
	ServiceResponse cancelBackup(BackupResult backupResultModel, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Extract the results of a backup. This is generally used for packaging up a full backup for the purposes of
	 * a download or full archive of the backup.
	 * @param backupResultModel the details associated with the results of the backup execution.
	 * @param opts additional options.
	 * @return a {@link ServiceResponse} indicating the success or failure of the backup extraction.
	 */
	@Override
	ServiceResponse extractBackup(BackupResult backupResultModel, Map opts) {
		return ServiceResponse.success()
	}

}		
