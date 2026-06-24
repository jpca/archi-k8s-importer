package fr.jpca.eclipse;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Status;


public class EclipseLogger {
    private ILog eclipseLog = null;
    private final String name;
    private final String pluginId;
    
    public EclipseLogger(String name, ILog log, String pluginId) {
        this.name = name;
        this.eclipseLog = log;
        this.pluginId = pluginId;
    }

	public boolean isRunningInEclipse() {
		return eclipseLog != null;
	}

    public void info(String msg) {
    	msg = buildMsg(msg);
    	if(isRunningInEclipse())
    		eclipseLog.log(new Status(Status.INFO, pluginId, msg));
    	else
    		System.out.println(msg);
    }

    public void error(String msg, Throwable t) {
    	msg = buildMsg(msg);
    	if(isRunningInEclipse())
    		eclipseLog.log(new Status(Status.ERROR, pluginId, msg, t));
    	else {
    		System.err.println(msg);
    		if (t != null)
    			t.printStackTrace();
    	}
    }
    
    public void error(String msg) {
    	error(msg, null);
    }
    
    String buildMsg(String msg)
    {
    	return name + ": " + msg; 
    }
}


