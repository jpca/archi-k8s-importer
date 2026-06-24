/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package fr.jpca.archi.k8simporter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.internal.WorkbenchWindow;

import com.archimatetool.model.IArchimateModel;

import fr.jpca.utils.ResourceReader;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1NamespaceList;


/**
 * Command Action Handler for Import
 */
@SuppressWarnings("nls")
public class K8sImportHandler extends AbstractHandler {
    
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
    	
    	IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);

        ILog.get().info("K8sImportHandler execute " + event.toString() + "\n\tCommand: " + event.getCommand().toString() + "\n\tParameters: " + event.getParameters().toString() + "(" + event.getParameters().values() + ")" );

        // Get active and selected model, if any
        IWorkbenchPart part = HandlerUtil.getActivePart(event);
        IArchimateModel model = part != null ? part.getAdapter(IArchimateModel.class) : null;
    	
        try {
        	
	        K8sImporter importer = new K8sImporter();
        	// TODO: manage cluster url & token
	        K8sCrawl k8s = new K8sCrawl(null, null);
	        V1NamespaceList nsl = null;
	        try {
	        	nsl = k8s.getNamespaces();
	        }
	        catch(RuntimeException re)
	        {
		    	String errorMessage = "K8S connection error: " + re.getMessage();
		    	ILog.get().error(errorMessage, re);
		    	// catch the error for a user warning message if namespace list is null on import dialog
	        }
            K8sImportDialog dialog = launchImportDialog(window, model, nsl);
           
            if(dialog.getNamespace() != null) // null if cancel button pressed
            {
		        Config.load(dialog.getConfigPath());
		        Config.set("domain", dialog.getDomain());
		        Config.set("namespace", dialog.getNamespace());
		        
	            if (Config.getString("cluster_url") == null || Config.getString("cluster_url").isEmpty())
	            {
	            	Config.set("cluster_url",k8s.getCoreApi().getApiClient().getBasePath());
	            }
		      
		        importer.doImport(model, k8s, dialog.getDomain(), dialog.getNamespace(), window);
		        
		        //TODO: auto save preferences
            }           	

        } catch (Exception e) {
	    	String errorMessage = "K8S Import Error " + e.getMessage();
	    	ILog.get().error(errorMessage, e);
	    	
	    	MessageBox errorDialog = new MessageBox(window.getShell(), SWT.ICON_ERROR | SWT.OK);
	    	errorDialog.setText("K8S Import error");
	    	errorDialog.setMessage(errorMessage);
	    	errorDialog.open();
	    }
        
        return null;
    }

	private K8sImportDialog launchImportDialog(IWorkbenchWindow window, IArchimateModel model, V1NamespaceList nsl) throws IOException {
		K8sImportDialog dialog = null;
		new K8sImporterPlugin(); // instanciate plugin for prefs reading
    	dialog = new K8sImportDialog(window.getShell());
    	if(nsl != null)
    		dialog.setNamespaceList(nsl);
    	if(dialog.open() == Window.OK) {

    		if(dialog.getConfigPath().equals(IPreferenceConstants.K8SIMPORTER_CONFIG_DEFAULT_LOCATION) 
    				&& ((new File(IPreferenceConstants.K8SIMPORTER_CONFIG_DEFAULT_LOCATION)).exists() == false))
    		{
    			createDefaultConfigFile();
    		}
    	}
    	return dialog;
	}
    
	private void createDefaultConfigFile() throws IOException {
		try (PrintWriter writer = new PrintWriter(new FileWriter(IPreferenceConstants.K8SIMPORTER_CONFIG_DEFAULT_LOCATION))) {
			try (InputStream is = ResourceReader.class.getResourceAsStream("/" + IPreferenceConstants.K8SIMPORTER_CONFIG_DEFAULT_FILENAME)) {
			    if (is == null) throw new IOException("Ressource unavailable in classpath");
			    String content;
			    try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
			        content = br.lines().collect(Collectors.joining("\n"));
			    }
			    writer.print(content);
			}
		}
	}

	// WIP for local test
    public static void main(String[] args) {
    	 // CLI options
        Options options = new Options();
        options.addOption("m", "model_file", true, "Model file");

        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine cmd = parser.parse(options, args);

            IArchimateModel model = null;
            // Override with CLI args
            if (cmd.hasOption("model_file"))
                model = K8sArchiConverter.loadModel(new File(cmd.getOptionValue("model_file")));
            
            Config.set("command_line", "true");
            
            K8sImportHandler handler = new K8sImportHandler();
            IWorkbenchWindow window = new WorkbenchWindow(null, null); // TODO CNFE
            handler.launchImportDialog(window, model, null);

        } catch (ParseException e) {
            System.err.println("Error parsing CLI arguments: " + e.getMessage());
            new HelpFormatter().printHelp("K8sArchiConverter", options);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    }

}