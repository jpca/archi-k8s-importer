package fr.jpca.archi.k8simporter;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import fr.jpca.eclipse.EclipseLogger;

/**
 * Activator
 * 
 */
public class K8sImporterPlugin extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "fr.jpca.archi.k8simporter"; //$NON-NLS-1$
    
    // The shared instance
    private static K8sImporterPlugin instance;
    
    /**
     * @return the shared instance
     */
    public static K8sImporterPlugin getInstance() {
    	ILog.get().info("K8sImporterPlugin getInstance");
        return instance;
    }

    public K8sImporterPlugin() {
        instance = this;
    }

    public InputStream getBundleInputStream(String bundleFileName) throws IOException {
        URL url = getBundle().getEntry(bundleFileName);
        return url.openStream();
    }
    
	public static boolean isRunningInEclipse() {
	    try {
	    	String val = Config.get("command_line");
	    	if (val != null && !val.equalsIgnoreCase("false"))
	    		return false;
	    		
	        Platform.getBundle("org.eclipse.core.runtime");
	        return true;
	    } catch (Throwable t) {
	        return false;
	    }
	}

	public static EclipseLogger getLogger(String loggerName) {
		ILog log = null;
		if (K8sImporterPlugin.isRunningInEclipse() && !isJUnitTest())
		{
			log = ILog.get();
		}
		
	    return new EclipseLogger(loggerName, log, PLUGIN_ID);
	}
	
	public static EclipseLogger getLogger(Class clazz) {
		return K8sImporterPlugin.getLogger(clazz.toString());
	}
    
	// Source - https://stackoverflow.com/a/12717377
	// Posted by Janning Vygen, modified by community. See post 'Timeline' for change history
	// Retrieved 2026-03-31, License - CC BY-SA 4.0

	public static boolean isJUnitTest() {  
	  for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
	    if (element.getClassName().startsWith("org.junit.")) {
	      return true;
	    }           
	  }
	  return false;
	}

	
}

