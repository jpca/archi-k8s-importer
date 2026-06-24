package fr.jpca.archi.k8simporter;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.archimatetool.editor.model.IArchiveManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IProperties;
import com.archimatetool.model.IProperty;

public class K8sArchiGenerationE2ETest {

    private static IArchimateModel testModel;
    private static String archiModelFile;
    private static String namespace;
    private static String controlFolder;
    private static String targetFolder;
    private static String clusterUrl;

    @BeforeAll
    public static void setup() throws Exception {
        archiModelFile = System.getProperty("archiModelFile", "./test.archimate");
        namespace = System.getProperty("k8sNamespace", "default");
        controlFolder = System.getProperty("controlFolder", "_TEST_CONTROL");
        targetFolder = System.getProperty("targetFolder", "_TEST_");
        // URL will default to what Config pulls from defaults if not set
        clusterUrl = System.getProperty("clusterUrl", "");
        String archiConfigFile = System.getProperty("archiConfigFile", "./archi-k8s-importer-config.yaml");
        
        boolean saveModel = Boolean.valueOf(System.getProperty("saveModel", "true"));
        

        File f = new File(archiModelFile);
        if (!f.exists()) {
            System.err.println("Warning: Test model file not found at " + f.getAbsolutePath()
                    + ", the test may fail or be skipped.");
        } else {
            testModel = K8sArchiConverter.loadModel(f);

            // Setup Config for the Importer
            Config.load(archiConfigFile);
            Config.set("namespace", namespace);
            Config.set("domain", targetFolder); // + "_" + System.currentTimeMillis());
            if (!clusterUrl.isEmpty()) {
                Config.set("cluster_url", clusterUrl);
            }

            // Note: Token and actual K8s connection need to be available in the test
            // environment.
            // When run from Maven, K8s credentials from ~/.kube/config will be used if
            // token is empty.
            K8sCrawl k8s = new K8sCrawl(Config.getString("cluster_url"), System.getProperty("k8sToken", null));
            if (clusterUrl.isEmpty())
            {
            	Config.set("cluster_url",k8s.getCoreApi().getApiClient().getBasePath());
            }
            K8sImporter importer = new K8sImporter();
            importer.doImport(testModel, k8s, targetFolder, namespace, null);
            
            if(saveModel)
            {
	            System.out.println("Saving updated model...");
	            
	            // Use Archive Manager to save contents
	            IArchiveManager archiveManager = (IArchiveManager)testModel.getAdapter(IArchiveManager.class);
	            if(archiveManager == null) {
	                archiveManager = IArchiveManager.FACTORY.createArchiveManager(testModel);
	                testModel.setAdapter(IArchiveManager.class, archiveManager);
	            }
	            archiveManager.saveModel();
	            System.out.println("Conversion kept in test model into " + Config.getString("domain"));
            }
            
        }
    }

    @Test
    public void testGenerationMatchesControl() {
        Assertions.assertNotNull(testModel, "Model should be loaded from " + archiModelFile);

        // Find Application Folders
        IFolder appRootFolder = testModel.getFolder(FolderType.APPLICATION);
        IFolder controlAppFolder = findSubFolder(appRootFolder, controlFolder);
        IFolder targetAppFolder = findSubFolder(appRootFolder, targetFolder);

        Assertions.assertNotNull(controlAppFolder, "Control folder '" + controlFolder + "' should exist in model");
        Assertions.assertNotNull(targetAppFolder, "Target folder '" + targetFolder + "' should have been generated");

        // Compare Application Elements
        List<String> report = new ArrayList<>();
        report.add("| Concept Name | Type | Status | Differences |");
        report.add("| --- | --- | --- | --- |");

        compareFolderElements(controlAppFolder, targetAppFolder, report);

        // Compare Technology & Physical Extracted Elements
        IFolder techRootFolder = testModel.getFolder(FolderType.TECHNOLOGY);
        IFolder controlTechFolder = findSubFolder(techRootFolder, controlFolder);
        IFolder targetTechFolder = findSubFolder(techRootFolder, targetFolder);

        if (controlTechFolder != null && targetTechFolder != null) {
            compareFolderElements(controlTechFolder, targetTechFolder, report);
        } else if (controlTechFolder != null) {
            report.add(String.format("| Folder %s | Technology Folder | FAIL | Missing generated technology folder |",
                    controlFolder));
        }

        // Output Report
        System.out.println("\n--------------------------------------------------------------");
        System.out.println("### Kubernetes to ArchiMate Test Report ###");
        System.out.println("--------------------------------------------------------------\n");
        for (String line : report) {
            System.out.println(line);
        }
        System.out.println("\n--------------------------------------------------------------\n");

        // Check for test failure
        boolean failed = report.stream().anyMatch(l -> l.contains("| FAIL |"));
        Assertions.assertFalse(failed,
                "There are differences between the generated folder and the control folder. See console report above.");
    }

    private IFolder findSubFolder(IFolder root, String name) {
        if (root == null)
            return null;
        for (IFolder f : root.getFolders()) {
            if (name.equals(f.getName()))
                return f;
        }
        return null;
    }

    private void compareFolderElements(IFolder controlFolder, IFolder targetFolder, List<String> report) {
        Map<String, IArchimateElement> controlElements = new HashMap<>();
        for (EObject el : controlFolder.getElements()) {
        	IArchimateElement ael = (IArchimateElement) el;
            controlElements.put(ael.getName() + "::" + ael.eClass().getName(), ael);
        }

        Map<String, IArchimateElement> targetElements = new HashMap<>();
        for (EObject el : targetFolder.getElements()) {
        	IArchimateElement ael = (IArchimateElement) el;
            targetElements.put(ael.getName() + "::" + ael.eClass().getName(), ael);
        }

        for (String key : controlElements.keySet()) {
            IArchimateElement controlEl = controlElements.get(key);
            IArchimateElement targetEl = targetElements.get(key);

            if (targetEl == null) {
                report.add(String.format("| %s | %s | FAIL | Missing in generated target folder |", controlEl.getName(),
                        controlEl.eClass().getName()));
                continue;
            }

            // Compare properties
            List<String> propDiffs = new ArrayList<>();
            Map<String, String> cProps = getPropsMap(controlEl);
            Map<String, String> tProps = getPropsMap(targetEl);

            for (String pKey : cProps.keySet()) {
                if (pKey.startsWith("generation_"))
                    continue; // Ignore generation_* props

                String cVal = cProps.get(pKey);
                String tVal = tProps.getOrDefault(pKey, "");

                if (!cVal.equals(tVal)) {
                    propDiffs.add(pKey + ": expected '" + cVal + "' got '" + tVal + "'");
                }
            }
            // Check unexpected props in target
            for (String pKey : tProps.keySet()) {
                if (pKey.startsWith("generation_"))
                    continue;
                if (!cProps.containsKey(pKey)) {
                    propDiffs.add(pKey + ": unexpected property in target");
                }
            }

            // Compare relations
            List<String> relDiffs = compareRelations(controlEl, targetEl);

            List<String> allDiffs = new ArrayList<>();
            allDiffs.addAll(propDiffs);
            allDiffs.addAll(relDiffs);

            if (allDiffs.isEmpty()) {
                report.add(String.format("| %s | %s | OK | None |", controlEl.getName(), controlEl.eClass().getName()));
            } else {
                report.add(String.format("| %s | %s | FAIL | %s |", controlEl.getName(), controlEl.eClass().getName(),
                        String.join("; ", allDiffs)));
            }

            targetElements.remove(key);
        }

        // Leftover generated elements not in control
        for (String key : targetElements.keySet()) {
            IArchimateElement el = targetElements.get(key);
            report.add(String.format("| %s | %s | FAIL | Unexpected generated concept |", el.getName(),
                    el.eClass().getName()));
        }

        // Also recurse into subfolders
        for (IFolder subC : controlFolder.getFolders()) {
            IFolder subT = findSubFolder(targetFolder, subC.getName());
            if (subT == null) {
                report.add(String.format("| Folder %s | Folder | FAIL | Missing generated folder |", subC.getName()));
            } else {
                compareFolderElements(subC, subT, report);
            }
        }
    }

    private Map<String, String> getPropsMap(IProperties owner) {
        Map<String, String> map = new HashMap<>();
        for (IProperty p : owner.getProperties()) {
            map.put(p.getKey(), p.getValue());
        }
        return map;
    }

    private List<String> compareRelations(IArchimateElement controlEl, IArchimateElement targetEl) {
        List<String> diffs = new ArrayList<>();
        Map<String, String> cRels = getRelationsMap(controlEl);
        Map<String, String> tRels = getRelationsMap(targetEl);

        for (String relId : cRels.keySet()) {
            if (!tRels.containsKey(relId)) {
                diffs.add("Missing relation: " + cRels.get(relId));
            }
        }
        for (String relId : tRels.keySet()) {
            if (!cRels.containsKey(relId)) {
                diffs.add("Unexpected relation: " + tRels.get(relId));
            }
        }

        return diffs;
    }

    private Map<String, String> getRelationsMap(IArchimateElement el) {
        // Build a canonical string representation of relations independent of their
        // exact EMF IDs.
        // E.g: "ServingRelationship->ServiceName"
        Map<String, String> map = new HashMap<>();
        for (IArchimateRelationship rel : el.getSourceRelationships()) {
            IArchimateConcept target = rel.getTarget();
            String relKey = rel.eClass().getName() + "->" + target.getName() + "::" + target.eClass().getName();
            map.put(relKey, "--> " + relKey);
        }
        for (IArchimateRelationship rel : el.getTargetRelationships()) {
            IArchimateConcept source = rel.getSource();
            String relKey = source.getName() + "::" + source.eClass().getName() + "->" + rel.eClass().getName();
            map.put(relKey, "<-- " + relKey);
        }
        return map;
    }
}
