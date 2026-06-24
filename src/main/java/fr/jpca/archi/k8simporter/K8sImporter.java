/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package fr.jpca.archi.k8simporter;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.ui.IWorkbenchWindow;

import com.archimatetool.model.IArchimateModel;

import fr.jpca.eclipse.EclipseLogger;


/**
 * Import K8S namespace into an Application sub folder
 */
@SuppressWarnings("nls")
public class K8sImporter {
	
	private static final EclipseLogger logger = K8sImporterPlugin.getLogger(K8sImporter.class);

 
    public void doImport(IArchimateModel model, K8sCrawl k8s, String domain, String namespace, IWorkbenchWindow window) {
	    try {
        	if((k8s.getNamespace(namespace) == null) && (k8s.getNamespaces() != null)) // namespace not found but namespaces exists
        		throw new RuntimeException("Namespace " + namespace + " not found");
        	
	    	logger.info("Starting conversion into Archi domain: " + domain);
	    	
	        // PVCs
	        var pvcList = k8s.getPvc(namespace);
	        ArchiK8sGen gen = new ArchiK8sGen(model);
	        gen.pvcToAppComponents(pvcList, domain);
	
	        // Pods
	        var podsList = k8s.getPods(namespace);
	        gen.podsToAppComponents(podsList, domain, k8s);
	        
	        // Services
	        var servicesList = k8s.getServices(namespace);
	        gen.servicesToAppInterfaces(servicesList, domain, k8s);
	        
	        // Ingresses
	        var ingressesList = k8s.getIngresses(namespace);
	        gen.ingressesToAppInterfaces(ingressesList, domain, k8s);

	        logger.info("Conversion completed.");
        
	    } catch (RuntimeException ae) {
	    	// popup application error
	    	if(window != null) {
		    	MessageBox dialog = new MessageBox(window.getShell(), SWT.ICON_INFORMATION | SWT.OK);
		    	dialog.setText("Kubernetes Import");
		    	dialog.setMessage(ae.getMessage());
		    	dialog.open();
	    	}	    	
	    } catch (Exception e) {
	    	StringWriter sw = new StringWriter();
	    	PrintWriter pw = new PrintWriter(sw);
	    	e.printStackTrace(pw);
	    	String errorMessage = "Error during to Kubernetes to Archi conversion: " + sw.toString();
	    	logger.error(errorMessage, e);
	    	
	    	if(window != null) {
		    	MessageBox dialog = new MessageBox(window.getShell(), SWT.ICON_ERROR | SWT.OK);
		    	dialog.setText("Kubernetes Import");
		    	dialog.setMessage(errorMessage);
		    	dialog.open();
	    	}
	    }
    }
}
