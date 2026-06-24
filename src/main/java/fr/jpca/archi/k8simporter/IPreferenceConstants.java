/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package fr.jpca.archi.k8simporter;




/**
 * Constant definitions for plug-in preferences
 * 
 */
public interface IPreferenceConstants {
	// prefs key
    String K8SIMPORTER_PREFS_LAST_NAMESPACE = "K8sImporterPrefsLastNamespace"; //$NON-NLS-1$
    String K8SIMPORTER_PREFS_LAST_DOMAIN = "K8sImporterPrefsLastDomain"; //$NON-NLS-1$
    String K8SIMPORTER_PREFS_CONFIG_LOCATION = "K8sImporterPrefsConfigLocation"; //$NON-NLS-1$
    String K8SIMPORTER_PREFS_LAST_CLUSTER_URL = "K8sImporterPrefsLastClusterURL"; //$NON-NLS-1$
    String K8SIMPORTER_PREFS_LAST_TOKEN = "K8sImporterPrefsLastToken"; //$NON-NLS-1$
    
    // other consts
	String K8SIMPORTER_CONFIG_DEFAULT_FILENAME = "archi-k8s-importer-config.yaml"; //$NON-NLS-1$;
	String K8SIMPORTER_CONFIG_DEFAULT_LOCATION = System.getProperty("user.home") + System.getProperty("file.separator") + K8SIMPORTER_CONFIG_DEFAULT_FILENAME;
    String K8SIMPORTER_PREFS_CONFIG_EXTENSION = "*.yaml"; //$NON-NLS-1$
}