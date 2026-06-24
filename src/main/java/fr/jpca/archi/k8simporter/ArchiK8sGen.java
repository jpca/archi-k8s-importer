package fr.jpca.archi.k8simporter;

import com.archimatetool.model.*;

import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.*;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;

import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.emf.common.util.EList;

/**
 * ArchiK8sGen
 * 
 * Generates ArchiMate elements directly into an IArchimateModel using the Archi
 * EMF API.
 * Replaces the XML generation logic of ArchiXmlGen.
 */
public class ArchiK8sGen {

    private static final Logger logger = Logger.getLogger(ArchiK8sGen.class.getName());

    private IArchimateModel model;

    public ArchiK8sGen(IArchimateModel model) {
        this.model = model;
    }

    /**
     * Convert PVCs to Application Components
     */
    public void pvcToAppComponents(V1PersistentVolumeClaimList pvcList, String domainName) {
        if (pvcList == null || pvcList.getItems() == null) {
            logger.info("No PVCs to convert.");
            return;
        }

        IFolder domainFolder = getOrCreateDomainFolder(domainName);

        // Identify unique app-components and their replicates (to handle multi-volume
        // pods)
        Map<String, Integer> appComponentReplicates = new HashMap<>();
        Map<String, Map<String, String>> appComponentPropertiesMap = new HashMap<>();

        for (V1PersistentVolumeClaim pvc : pvcList.getItems()) {
            String componentName = pvcToComponentName(pvc);

            // Collect properties for this component type
            Map<String, String> props = pvcAppComponentProperties(pvc);
            appComponentPropertiesMap.put(componentName, props);

            appComponentReplicates.put(componentName, appComponentReplicates.getOrDefault(componentName, 0) + 1);
        }

        // Generate Apps and ApplicationComponents
        String generationDate = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date());
        String namespace = Config.getString("namespace");

        for (String componentName : appComponentReplicates.keySet()) {
            // 1. Get/Create Application (parent architecture element)
            AppResult appResult = getAppFromComponent(componentName, domainFolder);
            IFolder appFolder = appResult.folder;
            IApplicationComponent app = appResult.application;

            // 2. Prepare component properties
            Map<String, String> componentProps = appComponentPropertiesMap.get(componentName);
            componentProps.put("pvc_replication", appComponentReplicates.get(componentName).toString());
            componentProps.put("component_type", "kubernetes-pvc");
            componentProps.put("generation_date", generationDate);
            componentProps.put("generation_namespace", namespace);

            // 3. Handle subfolder for PVCs if configured (e.g. "my-app-volumes")
            IFolder targetFolder = appFolder;
            if (Config.getBoolean("pvc_subfolder", true)) {
                String subfolderName = app.getName().toLowerCase()
                        + Config.getString("pvc_subfolder_suffix", "-volumes");
                targetFolder = findOrCreateFolder(appFolder, subfolderName);
            }

            // 4. Create/Update PVC ApplicationComponent
            IApplicationComponent pvcComponent = (IApplicationComponent) findOrCreateElement(targetFolder,
                    IArchimatePackage.eINSTANCE.getApplicationComponent(), componentName);

            // Update documentation and properties
            //updateElementDocumentation(pvcComponent, "Generated from K8s Importer " + namespace);

            for (Map.Entry<String, String> entry : componentProps.entrySet()) {
                setArchimateProperty(pvcComponent, entry.getKey(), entry.getValue());
            }

            // 5. Create CompositionRelationship (App -> PVC Component)
            createRelationship(IArchimatePackage.eINSTANCE.getCompositionRelationship(), app, pvcComponent);

            // 6. Associate with K8s Cluster Node (Realization)
            INode clusterNode = getK8sClusterNode();
            createRelationship(IArchimatePackage.eINSTANCE.getRealizationRelationship(), clusterNode, pvcComponent);

            logger.info("Converted PVC component: " + componentName + " for app: " + app.getName());
        }
    }

    private static class AppResult {
        IFolder folder;
        IApplicationComponent application;

        AppResult(IFolder f, IApplicationComponent a) {
            folder = f;
            application = a;
        }
    }
    
    private AppResult getAppFromComponent(String componentName, IFolder domainFolder) {
        String appName = componentToAppName(componentName);

        // Properties for the App element
        Map<String, String> appProps = new HashMap<>();
        appProps.put("component_type", "application");
        appProps.put("generation_date", new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date()));
        appProps.put("generation_namespace", Config.getString("namespace"));

        IFolder appFolder = findOrCreateFolder(domainFolder, appName);
        IApplicationComponent app = (IApplicationComponent) findOrCreateElement(appFolder,
                IArchimatePackage.eINSTANCE.getApplicationComponent(), appName);

        for (Map.Entry<String, String> entry : appProps.entrySet()) {
            setArchimateProperty(app, entry.getKey(), entry.getValue());
        }

        return new AppResult(appFolder, app);
    }

    private String pvcToComponentName(V1PersistentVolumeClaim pvc) {
        String prefix = "";
        String suffix = "";
        Map<String, String> labels = pvc.getMetadata().getLabels();

        if (labels != null) {
            List<String> prefixLabels = Config.getList("pvc_labels_for_component_prefix");
            if (prefixLabels != null) {
                for (String label : prefixLabels) {
                    if (labels.containsKey(label)) {
                        prefix = labels.get(label);
                        break;
                    }
                }
            }
            String suffixKey = Config.getString("pvc_label_for_component_suffix");
            if (suffixKey != null && labels.containsKey(suffixKey)) {
                suffix = labels.get(suffixKey);
            }
        }

        String componentName;
        if (prefix.isEmpty()) {
            if (!suffix.isEmpty()) {
                componentName = suffix;
            } else {
                logger.warning("failed to get component_name from labels for pvc " + pvc.getMetadata().getName()
                        + ", using real name");
                componentName = pvc.getMetadata().getName();
            }
        } else {
            componentName = (prefix + (suffix.isEmpty() ? "" : "-" + suffix)).trim();
        }

        componentName += Config.getString("pvc_name_last_suffix");

        // Aliases
        Map<String, String> aliases = Config.getMap("app_component_name_pvc_aliases");
        if (aliases != null) {
            for (Map.Entry<String, String> entry : aliases.entrySet()) {
                if (componentName.contains(entry.getKey())) {
                    componentName = entry.getValue();
                    break;
                }
            }
        }

        return componentName;
    }

    private Map<String, String> pvcAppComponentProperties(V1PersistentVolumeClaim pvc) {
        Map<String, String> props = new HashMap<>();
        props.put("pvc_name", normalizedPvcName(pvc.getMetadata().getName()));
        if (pvc.getSpec() != null) {
            props.put("storage_class", pvc.getSpec().getStorageClassName());
        }

        if (pvc.getStatus() != null) {
            if (pvc.getStatus().getCapacity() != null) {
                Quantity capacity = pvc.getStatus().getCapacity().get("storage");
                if (capacity != null)
                    props.put("storage_capacity", capacity.toString());
            }
            if (pvc.getStatus().getAccessModes() != null) {
                props.put("storage_access_modes", String.join(", ", pvc.getStatus().getAccessModes()));
            }
        }
        return props;
    }

    private String normalizedPvcName(String name) {
        Pattern p = Pattern.compile("(^.*)(-\\d*)");
        Matcher m = p.matcher(name);
        if (m.matches()) {
            return m.group(1);
        } else {
            logger.warning("PVC name " + name + " unrecognized, real PVC name used");
            return name;
        }
    }

    private String componentToAppName(String componentName) {
        String appName = null;

        // 1. Namespace as appname
        Map<String, String> nsAsApp = Config.getMap("namespace_as_appname");
        String namespace = Config.getString("namespace");
        if (nsAsApp != null) {
            for (Map.Entry<String, String> entry : nsAsApp.entrySet()) {
                if (Pattern.compile(entry.getKey(), Pattern.CASE_INSENSITIVE).matcher(namespace).matches()) {
                    appName = entry.getValue();
                    break;
                }
            }
        }

        // 2. Default derive from component name
        if (appName == null) {
            appName = componentName.split("-")[0].toUpperCase();
        }

        // 3. Domain custom mapping
        Map<String, Map<String, String>> customMap = Config.getMapOfMaps("custom_component_name_to_app_names");
        String domain = Config.getString("domain");
        if (customMap != null && customMap.containsKey(domain)) {
            Map<String, String> domainMap = customMap.get(domain);
            for (Map.Entry<String, String> entry : domainMap.entrySet()) {
                if (Pattern.compile(entry.getKey(), Pattern.CASE_INSENSITIVE).matcher(componentName).matches()) {
                    appName = entry.getValue();
                    break;
                }
            }
        }

        // 4. Prefixes
        List<String> domainPrefixes = Config.getList("app_names_with_domain_prefix");
        if (domainPrefixes != null) {
            for (String prefix : domainPrefixes) {
                if (appName.contains(prefix)) {
                    appName = domain + "-" + appName;
                    break;
                }
            }
        }
        List<String> nsPrefixes = Config.getList("app_names_with_namespace_prefix");
        if (nsPrefixes != null) {
            for (String prefix : nsPrefixes) {
                if (appName.contains(prefix)) {
                    appName = namespace + "-" + appName;
                    break;
                }
            }
        }

        // 5. App Replaces
        Map<String, String> replaces = Config.getMap("app_name_replaces");
        if (replaces != null) {
            for (Map.Entry<String, String> entry : replaces.entrySet()) {
                if (Pattern.compile(entry.getKey(), Pattern.CASE_INSENSITIVE).matcher(appName).matches()) {
                    appName = entry.getValue();
                    break;
                }
            }
        }

        return appName;
    }

    private INode getK8sClusterNode() {
        IFolder techFolder = model.getFolder(FolderType.TECHNOLOGY);
        String k8sRootFolderName = Config.getString("k8s_clusters_folder_name");
        IFolder rootK8sFolder = findOrCreateFolder(techFolder, k8sRootFolderName);

        IFolder parentFolder = rootK8sFolder;
        String clusterUrl = Config.getString("cluster_url");
        String alias = getK8sClusterAlias(clusterUrl);
        String[] folders = alias.split("/");

        for (int i = 0; i < folders.length - 1; i++) {
            parentFolder = findOrCreateFolder(parentFolder, folders[i]);
        }

        String clusterName = getK8sClusterName(clusterUrl);
        INode node = (INode) findOrCreateElement(parentFolder, IArchimatePackage.eINSTANCE.getNode(), clusterName);
        setArchimateProperty(node, "node_type", "kubernetes-cluster");
        setArchimateProperty(node, "uri", clusterUrl);

        return node;
    }

    private String getK8sClusterAlias(String url) {
    	if(url != null) {
	        Map<String, String> aliases = Config.getMap("k8s_clusters_aliases");
	        if (aliases != null) {
	            for (Map.Entry<String, String> entry : aliases.entrySet()) {
	                if (url.contains(entry.getKey()))
	                    return entry.getValue();
	            }
	        }
    	}
    	String unkownClusterName = "unknown-" + Config.getString("namespace");
        return unkownClusterName + "/" + unkownClusterName;
    }

    private String getK8sClusterName(String url) {
        String alias = getK8sClusterAlias(url);
        String name = alias.substring(alias.lastIndexOf("/") + 1);
        String suffix = Config.getString("k8s_clusters_name_suffix");
        return name + (suffix != null ? suffix : "");
    }

    private void createRelationship(EClass type, IArchimateElement source, IArchimateElement target) {
        // Find existing
        IFolder relFolder = model.getFolder(FolderType.RELATIONS);
        for (Object obj : relFolder.getElements()) {
            if (obj instanceof IArchimateRelationship) {
                IArchimateRelationship rel = (IArchimateRelationship) obj;
                if (rel.eClass().equals(type) && rel.getSource().equals(source) && rel.getTarget().equals(target)) {
                    return;
                }
            }
        }
        // Create
        IArchimateRelationship rel = (IArchimateRelationship) IArchimateFactory.eINSTANCE.create(type);
        rel.setSource(source);
        rel.setTarget(target);
        relFolder.getElements().add(rel);
    }

    /**
     * Convert Pods to Application Components
     */
    public void podsToAppComponents(V1PodList podsList, String domainName, K8sCrawl k8s) {
        if (podsList == null || podsList.getItems() == null)
            return;

        IFolder domainFolder = getOrCreateDomainFolder(domainName);

        // Identify unique app-components and their replicates
        Map<String, Integer> appComponentReplicates = new HashMap<>();
        Map<String, Map<String, Object>> appComponentInfo = new HashMap<>();

        for (V1Pod pod : podsList.getItems()) {
            String componentName = podToComponentName(pod);
            appComponentReplicates.put(componentName, appComponentReplicates.getOrDefault(componentName, 0) + 1);

            if (!appComponentInfo.containsKey(componentName)) {
                appComponentInfo.put(componentName, podAppComponentProperties(pod));
            }
        }

        String generationDate = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date());
        String namespace = Config.getString("namespace");

        for (String componentName : appComponentReplicates.keySet()) {
            // 1. Get/Create Application (parent)
            AppResult appResult = getAppFromComponent(componentName, domainFolder);
            IFolder appFolder = appResult.folder;
            IApplicationComponent app = appResult.application;

            Map<String, Object> info = appComponentInfo.get(componentName);
            Map<String, String> podProps = (Map<String, String>) info.get("_pod_properties_");

            // 2. Prepare workload properties
            podProps.put("replicates", appComponentReplicates.get(componentName).toString());
            podProps.put("component_type", "kubernetes-workload");
            podProps.put("generation_date", generationDate);
            podProps.put("generation_namespace", namespace);

            // 3. Create/Update workload component
            IApplicationComponent workloadComponent = (IApplicationComponent) findOrCreateElement(appFolder,
                    IArchimatePackage.eINSTANCE.getApplicationComponent(), componentName);

            //updateElementDocumentation(workloadComponent, "Generated from K8s Importer " + namespace);
            for (Map.Entry<String, String> entry : podProps.entrySet()) {
                setArchimateProperty(workloadComponent, entry.getKey(), entry.getValue());
            }

            // 4. Composition (App -> Workload)
            createRelationship(IArchimatePackage.eINSTANCE.getCompositionRelationship(), app, workloadComponent);

            // 5. Volume Claims (Workload -> PVC)
            String claimsStr = podProps.get("volume_claims");
            if (claimsStr != null && !claimsStr.equals("[]")) {
                // Simplified: assuming claims is a comma separated string or similar
                String[] claims = claimsStr.replace("[", "").replace("]", "").split(",");
                for (String claim : claims) {
                    claim = claim.trim();
                    if (claim.isEmpty())
                        continue;
                    String npn = normalizedPvcName(claim);
                    // Search for the PVC component in the same domain
                    IApplicationComponent pvcComp = findApplicationComponentInDomain(npn, domainFolder,
                            "kubernetes-pvc");
                    if (pvcComp != null) {
                        createRelationship(IArchimatePackage.eINSTANCE.getTriggeringRelationship(), workloadComponent,
                                pvcComp);
                    }
                }
            }

            // 6. Realization to Cluster Node
            INode clusterNode = getK8sClusterNode();
            createRelationship(IArchimatePackage.eINSTANCE.getRealizationRelationship(), clusterNode,
                    workloadComponent);

            // 7. Containers as sub-components
            for (Map.Entry<String, Object> entry : info.entrySet()) {
                String key = entry.getKey();
                if (key.equals("_pod_properties_") || key.equals("generation_date")
                        || key.equals("generation_namespace")) {
                    continue;
                }

                Map<String, String> containerProps = (Map<String, String>) entry.getValue();
                IApplicationComponent containerComp = getOrCreateContainerComponent(componentName, key, containerProps,
                        appFolder);

                // Composition (Workload -> Container)
                createRelationship(IArchimatePackage.eINSTANCE.getCompositionRelationship(), workloadComponent,
                        containerComp);

                // Docker Image (Artifact)
                ArtifactResult ssResult = getArtifact(containerComp);
                IArtifact sysSoft = ssResult.artifact;

                // Association (Container -> Image)
                createRelationship(IArchimatePackage.eINSTANCE.getAssociationRelationship(), containerComp, sysSoft);
            }
        }
    }

    private String podToComponentName(V1Pod pod) {
        // Alias check
        Map<String, String> podAliases = Config.getMap("app_component_name_pod_aliases");
        if (podAliases != null) {
            for (Map.Entry<String, String> entry : podAliases.entrySet()) {
            	Matcher m = Pattern.compile(entry.getKey(), Pattern.CASE_INSENSITIVE).matcher(pod.getMetadata().getName());
                if (m.matches()) {
                    return m.group(1); // TODO: parse entry.value() to match group expression
                }
            }
        }

        String prefix = "";
        String suffix = "";
        Map<String, String> labels = pod.getMetadata().getLabels();
        if (labels != null) {
            List<String> prefixLabels = Config.getList("pod_labels_for_component_prefix");
            if (prefixLabels != null) {
                for (String label : prefixLabels) {
                    if (labels.containsKey(label)) {
                        prefix = labels.get(label);
                        break;
                    }
                }
            }
            String suffixKey = Config.getString("pod_label_for_component_suffix");
            if (suffixKey != null && labels.containsKey(suffixKey)) {
                suffix = labels.get(suffixKey);
            }
        }

        if (prefix.isEmpty()) {
            if (!suffix.isEmpty()) {
                return suffix;
            } else {
                String pattern = Config.getString("unlabeled_pod_name_pattern");
                if (pattern != null) {
                    Matcher m = Pattern.compile(pattern).matcher(pod.getMetadata().getName());
                    if (m.find())
                        return m.group(1);
                }
                return pod.getMetadata().getName();
            }
        }
        return (prefix + (suffix.isEmpty() ? "" : "-" + suffix)).trim();
    }

    private Map<String, Object> podAppComponentProperties(V1Pod pod) {
        // This is pod_app_component_info in Python
        Map<String, Object> info = new HashMap<>();
        Map<String, String> podProps = new HashMap<>();
        info.put("_pod_properties_", podProps);

        //podProps.put("pod_uid", pod.getMetadata().getUid());

        // Volumes
        List<String> claims = new ArrayList<>();
        if (pod.getSpec().getVolumes() != null) {
            for (V1Volume vol : pod.getSpec().getVolumes()) {
                if (vol.getPersistentVolumeClaim() != null) {
                    claims.add(vol.getPersistentVolumeClaim().getClaimName());
                }
            }
        }
        podProps.put("volume_claims", claims.toString());

        // Tolerations
        List<String> podTolerations = new ArrayList<>();
        List<String> configTolerations = Config.getList("app_component_info_pod_tolerations");
        if (configTolerations != null && pod.getSpec().getTolerations() != null) {
            for (String tolerationKey : configTolerations) {
                for (V1Toleration pt : pod.getSpec().getTolerations()) {
                    if (tolerationKey.equals(pt.getKey())) {
                        podTolerations.add(String.format("%s if %s %s %s", pt.getEffect(), pt.getKey(),
                                pt.getOperator(), pt.getValue()));
                    }
                }
            }
        }
        if (!podTolerations.isEmpty()) {
            podProps.put("tolerations", podTolerations.toString());
        }

        // Node Selectors
        Map<String, String> podNodeSelectors = pod.getSpec().getNodeSelector();
        Map<String, String> configNodeSelectors = Config.getMap("app_component_info_pod_node_selectors");
        if (podNodeSelectors != null && configNodeSelectors != null) {
            for (Map.Entry<String, String> entry : configNodeSelectors.entrySet()) {
                if (podNodeSelectors.containsKey(entry.getKey())) {
                    podProps.put(entry.getValue(), podNodeSelectors.get(entry.getKey()));
                }
            }
        }

        // Labels (Prefix/Suffix/Mapped)
        Map<String, String> labels = pod.getMetadata().getLabels();
        if (labels != null) {
            List<String> prefixLabels = Config.getList("pod_labels_for_component_prefix");
            if (prefixLabels != null) {
                for (String labelKey : prefixLabels) {
                    if (labels.containsKey(labelKey)) {
                        podProps.put(labelKey, labels.get(labelKey));
                    }
                }
            }
            String suffixKey = Config.getString("pod_label_for_component_suffix");
            if (suffixKey != null && labels.containsKey(suffixKey)) {
                podProps.put(suffixKey, labels.get(suffixKey));
            }
            Map<String, String> mappedLabels = Config.getMap("app_component_info_pod_labels");
            if (mappedLabels != null) {
                for (Map.Entry<String, String> entry : mappedLabels.entrySet()) {
                    if (labels.containsKey(entry.getKey())) {
                        podProps.put(entry.getValue(), labels.get(entry.getKey()));
                    }
                }
            }
        }

        // Annotations
        Map<String, String> annotations = pod.getMetadata().getAnnotations();
        Map<String, String> mappedAnnotations = Config.getMap("app_component_info_pod_annotations");
        if (annotations != null && mappedAnnotations != null) {
            for (Map.Entry<String, String> entry : mappedAnnotations.entrySet()) {
                if (annotations.containsKey(entry.getKey())) {
                    podProps.put(entry.getValue(), annotations.get(entry.getKey()));
                }
            }
        }

        // Parse containers
        for (V1Container c : pod.getSpec().getContainers()) {
            info.put(c.getName(), parseContainer(c));
        }
        if (pod.getSpec().getInitContainers() != null) {
            for (V1Container c : pod.getSpec().getInitContainers()) {
                info.put(c.getName(), parseContainer(c));
            }
        }

        return info;
    }

    private Map<String, String> parseContainer(V1Container container) {
        Map<String, String> info = new HashMap<>();
        info.put("component_type", "docker-container");
        String image = container.getImage();
        if (image != null) {
            String[] parts = image.split(":");
            info.put("image_name", parts[0]);
            info.put("image_version", parts.length > 1 ? parts[1] : "latest");
        }

        if (container.getResources() != null) {
            if (container.getResources().getLimits() != null) {
                if (container.getResources().getLimits().containsKey("cpu"))
                    info.put("cpu", container.getResources().getLimits().get("cpu").toString());
                if (container.getResources().getLimits().containsKey("memory"))
                    info.put("memory", container.getResources().getLimits().get("memory").toString());
            }
            if (Config.getBoolean("container_properties_resources_requests", false)
                    && container.getResources().getRequests() != null) {
                if (container.getResources().getRequests().containsKey("cpu"))
                    info.put("cpu_request", container.getResources().getRequests().get("cpu").toString());
                if (container.getResources().getRequests().containsKey("memory"))
                    info.put("memory_request", container.getResources().getRequests().get("memory").toString());
            }
        }

        if (container.getPorts() != null) {
            List<String> ports = new ArrayList<>();
            for (V1ContainerPort p : container.getPorts()) {
                ports.add((p.getName() != null ? p.getName() : "unnamed") + ":" + p.getContainerPort());
            }
            info.put("ports", ports.toString());
        }

        return info;
    }

    private IApplicationComponent getOrCreateContainerComponent(String appComponentName, String containerName,
            Map<String, String> props, IFolder appFolder) {
        String archiName = containerName + Config.getString("container_name_suffix", "");
        IFolder targetFolder = appFolder;

        if (Config.getBoolean("container_subfolder", true)) {
            String subName = appComponentName + Config.getString("container_subfolder_suffix", "-containers");
            targetFolder = findOrCreateFolder(appFolder, subName);
        }

        IApplicationComponent comp = (IApplicationComponent) findOrCreateElement(targetFolder,
                IArchimatePackage.eINSTANCE.getApplicationComponent(), archiName);

        for (Map.Entry<String, String> entry : props.entrySet()) {
            setArchimateProperty(comp, entry.getKey(), entry.getValue());
        }
        return comp;
    }

    private static class ArtifactResult {
        IArtifact artifact;
        String version;
    }

    private ArtifactResult getArtifact(IApplicationComponent container) {
        String imageName = getArchimatePropertyValue(container, "image_name");
        String imageVersion = getArchimatePropertyValue(container, "image_version");

        // Apply replaces
        Map<String, String> replaces = Config.getMap("docker_image_name_replaces");
        if (replaces != null) {
            for (Map.Entry<String, String> entry : replaces.entrySet()) {
                if (imageName.contains(entry.getKey())) {
                    imageName = imageName.replace(entry.getKey(), entry.getValue());
                }
            }
        }

        String twoDigitsVersion = null;
        if (imageVersion != null) {
            Matcher m = Pattern.compile("^(\\w*\\W\\w*)").matcher(imageVersion);
            if (m.find())
                twoDigitsVersion = m.group();
        }

        String ssName = imageName + (twoDigitsVersion != null ? "_" + twoDigitsVersion : "");

        IFolder techFolder = model.getFolder(FolderType.TECHNOLOGY);
        IFolder rootImagesFolder = findOrCreateFolder(techFolder,
                Config.getString("docker_images_folder_name", "Docker Images"));

        IFolder targetFolder = rootImagesFolder;
        if (Config.getBoolean("docker_image_subfolder", true)) {
            String[] folders = ssName.split("/");
            for (int i = 0; i < folders.length - 1; i++) {
                targetFolder = findOrCreateFolder(targetFolder, folders[i]);
            }
        }

        IArtifact art = (IArtifact) findOrCreateElement(targetFolder,
                IArchimatePackage.eINSTANCE.getArtifact(), ssName);
        setArchimateProperty(art, "artifact_type", "docker-image");
        setArchimateProperty(art, "image_name", getArchimatePropertyValue(container, "image_name"));
        setArchimateProperty(art, "image_version", twoDigitsVersion);
        setArchimateProperty(art, "generation_date", new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date()));
        setArchimateProperty(art, "generation_namespace", Config.getString("namespace"));      

        ArtifactResult res = new ArtifactResult();
        res.artifact = art;
        res.version = twoDigitsVersion;
        return res;
    }

    private IApplicationComponent findApplicationComponentInDomain(String name, IFolder domainFolder, String type) {
        // Recursively search for a component with given name and type in the domain
        // folder
        return findApplicationComponentRecursive(domainFolder, name, type);
    }

    private IApplicationComponent findApplicationComponentRecursive(IFolder folder, String name, String type) {
        for (EObject obj : folder.getElements()) {
            if (obj instanceof IApplicationComponent) {
                IApplicationComponent comp = (IApplicationComponent) obj;
                if (comp.getName().equals(name)) {
                    if (type == null || type.equals(getArchimatePropertyValue(comp, "component_type"))) {
                        return comp;
                    }
                }
            }
        }
        for (IFolder sub : folder.getFolders()) {
            IApplicationComponent found = findApplicationComponentRecursive(sub, name, type);
            if (found != null)
                return found;
        }
        return null;
    }

    private String getArchimatePropertyValue(IProperties propertiesOwner, String key) {
        for (IProperty prop : propertiesOwner.getProperties()) {
            if (prop.getKey().equals(key))
                return prop.getValue();
        }
        return null;
    }

    /**
     * Convert Services to Application Interfaces
     */
    public void servicesToAppInterfaces(V1ServiceList servicesList, String domainName, K8sCrawl k8s) {
        if (servicesList == null || servicesList.getItems() == null)
            return;

        IFolder domainFolder = getOrCreateDomainFolder(domainName);

        for (V1Service service : servicesList.getItems()) {
            if (service.getSpec().getPorts() == null) {
                logger.info("Service " + service.getMetadata().getName() + " ignored (no ports)");
                continue;
            }

            for (V1ServicePort port : service.getSpec().getPorts()) {
                ServiceAppResult saResult = getAppComponentFromService(service, domainFolder, k8s);
                if (saResult.appComponent == null) {
                    logger.warning("Service " + service.getMetadata().getName() + ": no matching app-component found");
                    continue;
                }

                IApplicationComponent appComp = saResult.appComponent;
                IFolder appFolder = saResult.folder;
                V1Pod examplePod = saResult.pod;

                String interfaceName = appComp.getName() + "-"
                        + (port.getName() != null ? port.getName() : port.getPort());

                // Properties
                Map<String, String> props = new HashMap<>();
                props.put("interface_type", "kubernetes-service");
                props.put("service_name", service.getMetadata().getName());
                props.put("service_port_name", port.getName());
                props.put("service_port", port.getPort().toString());
                props.put("protocol", port.getProtocol());

                // Labels mapping
                Map<String, String> labels = service.getMetadata().getLabels();
                if (labels != null) {
                    Map<String, String> labelMap = Config.getMap("app_interface_info_service_labels");
                    if (labelMap != null) {
                        for (Map.Entry<String, String> entry : labelMap.entrySet()) {
                            if (labels.containsKey(entry.getKey())) {
                                props.put(entry.getValue(), labels.get(entry.getKey()));
                            }
                        }
                    }
                }

                if (props.containsKey("service_application")) {
                    interfaceName = appComp.getName() + "-" + props.get("service_application") + "-"
                            + (port.getName() != null ? port.getName() : port.getPort());
                }

                String suffix = Config.getString("service_name_last_suffix");
                if (suffix != null)
                    interfaceName += suffix;

                // Subfolder
                IFolder targetFolder = appFolder;
                if (Config.getBoolean("service_subfolder", true)) {
                    String subName = appComp.getName() + Config.getString("service_subfolder_suffix", "-services");
                    targetFolder = findOrCreateFolder(appFolder, subName);
                }

                // Create ApplicationInterface
                IApplicationInterface appInterface = (IApplicationInterface) findOrCreateElement(targetFolder,
                        IArchimatePackage.eINSTANCE.getApplicationInterface(), interfaceName);

                for (Map.Entry<String, String> entry : props.entrySet()) {
                    setArchimateProperty(appInterface, entry.getKey(), entry.getValue());
                }

                // Composition (Workload -> Interface)
                createRelationship(IArchimatePackage.eINSTANCE.getCompositionRelationship(), appComp, appInterface);

                // Triggering (Interface -> Container)
                IApplicationComponent targetContainer = getContainerFromServicePort(appFolder, appComp, port,
                        examplePod);
                if (targetContainer != null) {
                    createRelationship(IArchimatePackage.eINSTANCE.getTriggeringRelationship(), appInterface,
                            targetContainer);
                }

                // NodePort / LoadBalancer specific Nodes
                String svcType = service.getSpec().getType();
                Map<String, String> nodeSuffixes = Config.getMap("generated_service_node_types_suffix");
                if (nodeSuffixes != null && nodeSuffixes.containsKey(svcType)) {
                    String nodeType = "kubernetes-" + svcType.toLowerCase();
                    Map<String, String> nodeProps = new HashMap<>();
                    nodeProps.put("node_type", nodeType);
                    nodeProps.put("load_balancer_ip", service.getSpec().getLoadBalancerIP());
                    nodeProps.put("cluster_ip", service.getSpec().getClusterIP());

                    // Annotations
                    Map<String, String> annotations = service.getMetadata().getAnnotations();
                    if (annotations != null) {
                        Map<String, String> annoMap = Config.getMap("node_info_service_annotations");
                        if (annoMap != null) {
                            for (Map.Entry<String, String> entry : annoMap.entrySet()) {
                                if (annotations.containsKey(entry.getKey())) {
                                    nodeProps.put(entry.getValue(), annotations.get(entry.getKey()));
                                }
                            }
                        }
                    }

                    String nodeName = service.getMetadata().getName();
                    List<String> lbLabels = Config.getList("service_node_label_for_service_name");
                    if (lbLabels != null && labels != null) {
                        for (String lbLabel : lbLabels) {
                            if (labels.containsKey(lbLabel)) {
                                nodeName = labels.get(lbLabel);
                                break;
                            }
                        }
                    }
                    String nSuffix = nodeSuffixes.get(svcType);
                    if (nSuffix != null)
                        nodeName += nSuffix;

                    INode clusterNode = getK8sClusterNode();
                    IFolder clusterFolder = (IFolder) clusterNode.eContainer();

                    IFolder nodeFolder = clusterFolder;
                    String subfolder = Config.getString("service_nodes_subfolder_name");
                    if (subfolder != null) {
                        nodeFolder = findOrCreateFolder(clusterFolder, subfolder);
                    }

                    INode svcNode = (INode) findOrCreateElement(nodeFolder, IArchimatePackage.eINSTANCE.getNode(),
                            nodeName);
                    for (Map.Entry<String, String> entry : nodeProps.entrySet()) {
                        setArchimateProperty(svcNode, entry.getKey(), entry.getValue());
                    }

                    // Relation: Node -> Interface (Triggering)
                    createRelationship(IArchimatePackage.eINSTANCE.getTriggeringRelationship(), svcNode, appInterface);

                    // Relation: Cluster -> Node (Composition)
                    createRelationship(IArchimatePackage.eINSTANCE.getCompositionRelationship(), clusterNode, svcNode);
                }
            }
        }
    }

    private static class ServiceAppResult {
        IFolder folder;
        IApplicationComponent appComponent;
        V1Pod pod;
    }

    private ServiceAppResult getAppComponentFromService(V1Service service, IFolder domainFolder, K8sCrawl k8s) {
        ServiceAppResult result = new ServiceAppResult();
        if (service.getSpec().getSelector() == null)
            return result;

        V1PodList pods = k8s.findPodsInCacheByLabelsSelector(service.getSpec().getSelector());
        if (pods != null && !pods.getItems().isEmpty()) {
            V1Pod pod = pods.getItems().get(0);
            result.pod = pod;
            String compName = podToComponentName(pod);
            String appName = componentToAppName(compName);

            IFolder appFolder = findFolderRecursive(domainFolder, appName);
            if (appFolder != null) {
                result.folder = appFolder;
                result.appComponent = findApplicationComponentRecursive(appFolder, compName, "kubernetes-workload");
            }
        }
        return result;
    }

    private IFolder findFolderRecursive(IFolder parent, String name) {
        for (IFolder f : parent.getFolders()) {
            if (f.getName().equals(name))
                return f;
            IFolder found = findFolderRecursive(f, name);
            if (found != null)
                return found;
        }
        return null;
    }

    private IApplicationComponent getContainerFromServicePort(IFolder appFolder, IApplicationComponent appComp,
            V1ServicePort port, V1Pod pod) {
        if (pod == null)
            return null;
        for (V1Container c : pod.getSpec().getContainers()) {
            if (c.getPorts() != null) {
                for (V1ContainerPort cp : c.getPorts()) {
                    // Match by name or port number
                	String cpName = cp.getName();
                	String cpPort = cp.getContainerPort().toString();
                	if(port.getTargetPort() != null) {
                		String pPort = port.getTargetPort().toString();
                		if (pPort.equals(cpName) || pPort.equals(cpPort)) {
                             return findApplicationComponentRecursive(appFolder,
                                c.getName() + Config.getString("container_name_suffix", ""), "docker-container");
                		}
                    }
                }
            }
        }
        return null;
    }

    /**
     * Convert Ingresses to Application Interfaces (Technology?)
     */
    public void ingressesToAppInterfaces(V1IngressList ingressesList, String domainName, K8sCrawl k8s) {
        if (ingressesList == null || ingressesList.getItems() == null)
            return;

        INode clusterNode = getK8sClusterNode();
        IFolder clusterFolder = (IFolder) clusterNode.eContainer();

        for (V1Ingress ingress : ingressesList.getItems()) {
            if (ingress.getSpec().getTls() == null || ingress.getSpec().getRules() == null) {
                logger.info("Ingress " + ingress.getMetadata().getName() + " ignored (no TLS/Rules)");
                continue;
            }

            for (V1IngressTLS tls : ingress.getSpec().getTls()) {
                if (tls.getHosts() == null || tls.getHosts().isEmpty())
                    continue;

                String interfaceName = tls.getHosts().get(0);
                Map<String, String> labels = ingress.getMetadata().getLabels();
                List<String> lbLabels = Config.getList("ingress_label_for_interface_name");
                if (lbLabels != null && labels != null) {
                    for (String lbLabel : lbLabels) {
                        if (labels.containsKey(lbLabel)) {
                            interfaceName = labels.get(lbLabel);
                            break;
                        }
                    }
                }

                Map<String, String> props = new HashMap<>();
                props.put("interface_type", "kubernetes-ingress");
                props.put("ingress_class_name", ingress.getSpec().getIngressClassName());
                props.put("endpoint_fqdn", tls.getHosts().get(0));

                Map<String, String> annotations = ingress.getMetadata().getAnnotations();
                if (annotations != null) {
                    Map<String, String> annoMap = Config.getMap("tech_interface_info_ingress_annotations");
                    if (annoMap != null) {
                        for (Map.Entry<String, String> entry : annoMap.entrySet()) {
                            if (annotations.containsKey(entry.getKey())) {
                                String val = annotations.get(entry.getKey());

                                // Specific Rancher publicEndpoints handling
                                if (entry.getKey().equals("field.cattle.io/publicEndpoints")) {
                                    try {
                                        // Simplified parser as we don't have a JSON lib easily mapped here without
                                        // extra deps
                                        // In a real plugin, use Jackson/Gson or the one provided by K8s client
                                        // For now, mirroring Python's logic in spirit
                                        props.put(entry.getValue(), val);
                                    } catch (Exception e) {
                                        logger.warning("Failed to parse field.cattle.io/publicEndpoints");
                                    }
                                } else {
                                    props.put(entry.getValue(), val);
                                }
                            }
                        }
                    }
                }

                String suffix = Config.getString("ingress_name_suffix");
                if (suffix != null)
                    interfaceName += suffix;

                IFolder targetFolder = clusterFolder;
                String subfolder = Config.getString("ingress_subfolder_name");
                if (subfolder != null) {
                    targetFolder = findOrCreateFolder(clusterFolder, subfolder);
                }

                // Create TechnologyInterface
                ITechnologyInterface techInterface = (ITechnologyInterface) findOrCreateElement(targetFolder,
                        IArchimatePackage.eINSTANCE.getTechnologyInterface(), interfaceName);

                for (Map.Entry<String, String> entry : props.entrySet()) {
                    setArchimateProperty(techInterface, entry.getKey(), entry.getValue());
                }

                // Composition (Cluster -> TechInterface)
                createRelationship(IArchimatePackage.eINSTANCE.getCompositionRelationship(), clusterNode,
                        techInterface);

                // Triggering (TechInterface -> AppInterface)
                IApplicationInterface appInterface = getAppInterfaceFromIngressRule(ingress.getSpec().getRules(),
                        domainName);
                if (appInterface == null) {
                    // Try by name matching
                    appInterface = findAppInterfaceByProperty("service_name", ingress.getMetadata().getName(),
                            domainName);
                }

                if (appInterface != null) {
                    createRelationship(IArchimatePackage.eINSTANCE.getTriggeringRelationship(), techInterface,
                            appInterface);
                } else {
                    logger.warning("Ingress " + ingress.getMetadata().getName() + ": unable to find triggered service");
                }
            }
        }
    }

    private IApplicationInterface getAppInterfaceFromIngressRule(List<V1IngressRule> rules, String domainName) {
        if (rules == null)
            return null;
        for (V1IngressRule rule : rules) {
            if (rule.getHttp() != null && rule.getHttp().getPaths() != null) {
                for (V1HTTPIngressPath path : rule.getHttp().getPaths()) {
                    if (path.getBackend() != null && path.getBackend().getService() != null) {
                        String svcName = path.getBackend().getService().getName();
                        IApplicationInterface found = findAppInterfaceByProperty("service_name", svcName, domainName);
                        if (found != null)
                            return found;
                    }
                }
            }
        }
        return null;
    }

    private IApplicationInterface findAppInterfaceByProperty(String key, String value, String domainName) {
        IFolder domainFolder = getOrCreateDomainFolder(domainName);
        return findAppInterfaceRecursive(domainFolder, key, value);
    }

    private IApplicationInterface findAppInterfaceRecursive(IFolder folder, String key, String value) {
        for (EObject obj : folder.getElements()) {
            if (obj instanceof IApplicationInterface) {
                IApplicationInterface inf = (IApplicationInterface) obj;
                String propVal = getArchimatePropertyValue(inf, key);
                if (value.equals(propVal)) {
                    String type = getArchimatePropertyValue(inf, "interface_type");
                    if ("kubernetes-service".equals(type))
                        return inf;
                }
            }
        }
        for (IFolder sub : folder.getFolders()) {
            IApplicationInterface found = findAppInterfaceRecursive(sub, key, value);
            if (found != null)
                return found;
        }
        return null;
    }

    // --- Helpers ---

    private IFolder getOrCreateDomainFolder(String domainName) {
        // Assume we work in the 'Application' layer folder
        IFolder appFolder = model.getFolder(FolderType.APPLICATION);
        return findOrCreateFolder(appFolder, domainName);
    }

    private IFolder findOrCreateFolder(IFolder parent, String name) {
        for (IFolder f : parent.getFolders()) {
            if (f.getName().equals(name)) {
                return f;
            }
        }
        IFolder newFolder = IArchimateFactory.eINSTANCE.createFolder();
        newFolder.setName(name);
        parent.getFolders().add(newFolder);
        return newFolder;
    }

    private IArchimateElement findOrCreateElement(IFolder folder, EClass type, String name) {
        // Search in folder
        for (Object obj : folder.getElements()) {
            if (obj instanceof IArchimateElement) {
                IArchimateElement element = (IArchimateElement) obj;
                if (element.eClass().equals(type) && element.getName().equals(name)) {
                    return element;
                }
            }
        }
        // Create
        IArchimateElement element = (IArchimateElement) IArchimateFactory.eINSTANCE.create(type);
        element.setName(name);
        setArchimateProperty(element, "generation_date", new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date()));
        setArchimateProperty(element, "generation_namespace", Config.getString("namespace"));
        folder.getElements().add(element);
        return element;
    }

    private void updateElementDocumentation(IArchimateElement element, String info) {
        String generatedDoc = "--generated-begin--\n" + info + "\n--generated-end--";
        String currentDoc = element.getDocumentation();

        if (Config.getBoolean("documentation_overwrite", false)) {
            element.setDocumentation(generatedDoc);
        } else {
            if (currentDoc != null && currentDoc.contains("--generated-begin--")) {
                // Simplified regex replacement for the generated block
                String newDoc = currentDoc.replaceAll("(?s)--generated-begin--.*?--generated-end--", generatedDoc);
                element.setDocumentation(newDoc);
            } else {
                if (currentDoc == null || currentDoc.isEmpty()) {
                    element.setDocumentation(generatedDoc);
                } else {
                    element.setDocumentation(currentDoc + "\n" + generatedDoc);
                }
            }
        }
    }

    private void setArchimateProperty(IProperties propertiesOwner, String key, String value) {
        if (value == null)
            return;

        // Handle property_overwrite if needed (simplified merge is default)
        for (IProperty prop : propertiesOwner.getProperties()) {
            if (prop.getKey().equals(key)) {
                prop.setValue(value);
                return;
            }
        }
        IProperty prop = IArchimateFactory.eINSTANCE.createProperty();
        prop.setKey(key);
        prop.setValue(value);
        propertiesOwner.getProperties().add(prop);
    }
}
