package fr.jpca.archi.k8simporter;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;




/**
 * Class used to initialize default preference values
 * 
 */
public class PreferenceInitializer extends AbstractPreferenceInitializer
implements IPreferenceConstants {

    @Override
    public void initializeDefaultPreferences() {
        IPreferenceStore store = K8sImporterPlugin.getInstance().getPreferenceStore();
        
        store.setDefault(K8SIMPORTER_PREFS_LAST_DOMAIN, "TEST");
        store.setDefault(K8SIMPORTER_PREFS_LAST_NAMESPACE, "default");
        store.setDefault(K8SIMPORTER_PREFS_CONFIG_LOCATION, K8SIMPORTER_CONFIG_DEFAULT_LOCATION);
        
        store.setDefault(K8SIMPORTER_PREFS_LAST_CLUSTER_URL, "https://127.0.0.1:6443"); // Rancher Desktop
        store.setDefault(K8SIMPORTER_PREFS_LAST_TOKEN, "CHANGE ME");
    }
}
