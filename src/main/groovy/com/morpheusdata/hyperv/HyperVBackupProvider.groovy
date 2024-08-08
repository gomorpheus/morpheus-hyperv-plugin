package com.morpheusdata.hyperv

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.backup.AbstractBackupProvider
import com.morpheusdata.core.backup.BackupJobProvider
import com.morpheusdata.core.backup.DefaultBackupJobProvider
import com.morpheusdata.core.backup.MorpheusBackupProvider
import com.morpheusdata.model.BackupProvider as BackupProviderModel
import com.morpheusdata.model.Icon
import com.morpheusdata.model.OptionType
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class HyperVBackupProvider extends MorpheusBackupProvider {

	BackupJobProvider backupJobProvider;

	HyperVBackupProvider(Plugin plugin, MorpheusContext morpheusContext) {
		super(plugin, morpheusContext)

		HyperVBackupTypeProvider backupTypeProvider = new HyperVBackupTypeProvider(plugin, morpheus)
		plugin.registerProvider(backupTypeProvider)
		addScopedProvider(backupTypeProvider, "hyperv", null)
	}

}
