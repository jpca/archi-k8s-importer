package fr.jpca.archi.k8simporter;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.eclipse.emf.ecore.resource.Resource;

import com.archimatetool.editor.model.IArchiveManager;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.util.ArchimateResourceFactory;

import io.kubernetes.client.openapi.ApiClient;

public class K8sArchiConverter {

    public static void main(String[] args) {
        // CLI options
        Options options = new Options();
        options.addOption("c", "cluster_url", true, "Kubernetes Cluster URL");
        options.addOption("t", "token", true, "Kubernetes API Token");
        options.addOption("n", "namespace", true, "Target Namespace");
        options.addOption("d", "domain", true, "Target Archi Domain");
        options.addOption("m", "model_file", true, "Model file");
        options.addOption("e", "extV1beta1", true, "Use Extensions/v1beta1 API");

        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine cmd = parser.parse(options, args);

            String configPath = System.getenv("K8S_ARCHI_CONVERTER_CONFIG");
            if(configPath == null)
            	configPath = IPreferenceConstants.K8SIMPORTER_CONFIG_DEFAULT_FILENAME;
            Config.load(configPath);

            IArchimateModel model = null;
            // Override with CLI args
            if (cmd.hasOption("model_file"))
                model = loadModel(new File(cmd.getOptionValue("model_file")));
            if (cmd.hasOption("domain"))
                Config.set("domain", cmd.getOptionValue("domain"));
            if (cmd.hasOption("namespace"))
                Config.set("namespace", cmd.getOptionValue("namespace"));
            if (cmd.hasOption("cluster_url"))
                Config.set("cluster_url", cmd.getOptionValue("cluster_url"));
            else {
            	ApiClient client = io.kubernetes.client.util.Config.defaultClient();
            	Config.set("cluster_url",client.getBasePath());
            }
            if (cmd.hasOption("extV1beta1")) {
                String val = cmd.getOptionValue("extV1beta1");
                if (val != null && !val.equalsIgnoreCase("false")) {
                    Config.set("v1beta_ingress_api", true);
                }
            }
            
            Config.set("command_line", "true");

            String clusterUrl = Config.getString("cluster_url");
            String token = cmd.getOptionValue("token"); // Token usually not in config file for security

            K8sCrawl k8s = new K8sCrawl(clusterUrl, token);
            String namespace = Config.getString("namespace");
            String domain = Config.getString("domain");
            
            K8sImporter importer = new K8sImporter();
            importer.doImport(model, k8s, domain, namespace, null);
            
            System.out.println("Saving updated model...");
            
            // Use Archive Manager to save contents
            IArchiveManager archiveManager = (IArchiveManager)model.getAdapter(IArchiveManager.class);
            if(archiveManager == null) {
                archiveManager = IArchiveManager.FACTORY.createArchiveManager(model);
                model.setAdapter(IArchiveManager.class, archiveManager);
            }
            archiveManager.saveModel();
            System.out.println("Conversion done.");
            

        } catch (ParseException e) {
            System.err.println("Error parsing CLI arguments: " + e.getMessage());
            new HelpFormatter().printHelp("K8sArchiConverter", options);
            System.exit(1);
        } catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    }
    
    /**
     * Load the basic Archimate model with no added value of adapters, command stacks etc.
     * @return The model
     * @throws IOException
     */
    static IArchimateModel loadModel(File file) throws IOException {
        if(file == null) {
            throw new IllegalArgumentException("File is null");
        }
        
        if(!file.exists()) {
            throw new IllegalArgumentException("File does not exist: " + file);
        }

        Resource resource = ArchimateResourceFactory.createNewResource(file);
        resource.load(null);
        IArchimateModel model = (IArchimateModel)resource.getContents().get(0);
        
        // Set file
        model.setFile(file);
        
        return model;
    }
}
