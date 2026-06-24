package fr.jpca.archi.k8simporter;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.NetworkingV1Api;
// Check if V1beta1 APIs are needed / available in the client version used.
// Usually newer clients map Extensions/v1beta1 to networking.k8s.io/v1 logic or provide generated classes.
// For simplicity we'll focus on NetworkingV1Api which is standard for Ingress in modern clusters.
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.ClientBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class K8sCrawl {

    private String clusterUrl;
    private String apiKey;
    private boolean loadKubeConfig = true;

    private CoreV1Api coreApi;
    private NetworkingV1Api networkApi;

    private V1PodList podsListCache;
    private V1PersistentVolumeClaimList pvcListCache;

    private static final Logger logger = Logger.getLogger("k8s");

    public K8sCrawl(String clusterUrl, String apiKey) {
        this.clusterUrl = clusterUrl;
        if (apiKey != null && !apiKey.isEmpty()) {
            this.apiKey = apiKey;
            this.loadKubeConfig = false;
        }
    }

    private ApiClient getApiClient() throws IOException {
        if (loadKubeConfig) {
            logger.info("connecting to client using kubeconfig with current context");
            // Loading default kubeconfig
            return ClientBuilder.defaultClient();
        } else {
            logger.info("connecting to client from cluster " + clusterUrl);
            boolean verifySsl = Config.getBoolean("k8s_client_verify_ssl");
            return new ClientBuilder()
                    .setBasePath(clusterUrl)
                    .setVerifyingSsl(verifySsl)
                    .setAuthentication(new io.kubernetes.client.util.credentials.AccessTokenAuthentication(apiKey))
                    // .setCertificatePath(Config.getString("k8s_client_ssl_ca_cert")) // if needed
                    .build();
        }
    }

    public CoreV1Api getCoreApi() {
        if (coreApi == null) {
            try {
                coreApi = new CoreV1Api(getApiClient());
            } catch (IOException e) {
                logger.severe("Error init CoreApi: " + e.getMessage());
                throw new RuntimeException(e);
            }
        }
        return coreApi;
    }

    public NetworkingV1Api getNetworkApi() {
        if (networkApi == null) {
            try {
                networkApi = new NetworkingV1Api(getApiClient());
            } catch (IOException e) {
                logger.severe("Error init NetworkApi: " + e.getMessage());
                throw new RuntimeException(e);
            }
        }
        return networkApi;
    }

    public V1PersistentVolumeClaimList getPvc(String namespace) {
        try {
            logger.info("get_pvc from namespace " + namespace);
            //pvcListCache = getCoreApi().listNamespacedPersistentVolumeClaim(namespace, "true", null, null, null, null, null, null, null, 10, false);
            pvcListCache = getCoreApi().listNamespacedPersistentVolumeClaim(namespace).execute();
            return pvcListCache;
        } catch (ApiException e) {
            logger.severe("Error at kubernetes call: " + e.getResponseBody());
            throw new RuntimeException("K8s error " + e.getMessage(), e);
        }
    }

    public V1PodList getPods(String namespace) {
        try {
            logger.info("get_pods from namespace " + namespace);
            //podsListCache = getCoreApi().listNamespacedPod(namespace, "true", null, null, "status.phase=Running", null, null, null, null, 10, false);
            podsListCache = getCoreApi().listNamespacedPod(namespace).execute();
            return podsListCache;
        } catch (ApiException e) {
            logger.severe("Error at kubernetes call: " + e.getResponseBody());
            throw new RuntimeException("K8s error " + e.getMessage(), e);
        }
    }

    public V1ServiceList getServices(String namespace) {
        try {
            logger.info("get_services from namespace " + namespace);
            //return getCoreApi().listNamespacedService(namespace, "true", null, null, null, null, null, null, null, 10, false);
            return getCoreApi().listNamespacedService(namespace).execute();
        } catch (ApiException e) {
            logger.severe("Error at kubernetes call: " + e.getResponseBody());
            throw new RuntimeException("K8s error " + e.getMessage(), e);
        }
    }

    public V1NamespaceList getNamespaces() {
        try {
            logger.info("get_namespaces");
            return getCoreApi().listNamespace().execute();
        } catch (ApiException e) {
            logger.severe("Error at kubernetes call: " + e.getResponseBody());
            throw new RuntimeException("K8s error " + e.getMessage(), e);
        }
    }
    
    public V1Namespace getNamespace(String namespace) {
    	logger.info("get_namespace " + namespace);
    	if(getNamespaces() != null)
        {
	        for (V1Namespace ns : getNamespaces().getItems()) {
	        	if (namespace.equals(ns.getMetadata().getName()))
	        		return ns;
	        }
        }
        return null;
    }
    
    public V1IngressList getIngresses(String namespace) {
        try {
            logger.info("get_ingresses from namespace " + namespace);
            // Handling Extensions/V1beta1 vs Networking/V1 is up to the client
            // compatibility.
            // Using NetworkingV1Api for now.
            //return getNetworkApi().listNamespacedIngress(namespace, "true", null, null, null, null, null, null, null, 10, false);
            return getNetworkApi().listNamespacedIngress(namespace).execute();
        } catch (ApiException e) {
            logger.severe("Error at kubernetes call: " + e.getResponseBody());
            throw new RuntimeException("K8s error " + e.getMessage(), e);
        }
    }

    public List<V1Pod> findPodByName(String podName, boolean useCache) {
        logger.fine("find_pod with name " + podName + " use_cache=" + useCache);
        List<V1Pod> result = new ArrayList<>();

        if (useCache && podsListCache != null) {
            for (V1Pod pod : podsListCache.getItems()) {
                if (pod.getMetadata() != null && podName.equals(pod.getMetadata().getName())) {
                    result.add(pod);
                    return result;
                }
            }
        }

        try {
            //V1Pod pod = getCoreApi().readNamespacedPod(podName, Config.getString("namespace"), "true");
        	V1Pod pod = getCoreApi().readNamespacedPod(podName, Config.getString("namespace")).execute();
            result.add(pod);
            return result;
        } catch (ApiException e) {
            logger.severe("Error at kubernetes call: " + e.getResponseBody());
        }
        return result;
    }

    public V1PodList findPodsInCacheByLabelsSelector(Map<String, String> labels) {
        if (podsListCache == null) {
            throw new RuntimeException("pods_list_cache is empty, please call get_pods() before find_pod in_cache");
        }

        V1PodList result = new V1PodList();
        for (V1Pod pod : podsListCache.getItems()) {
            if (pod.getMetadata() == null || pod.getMetadata().getLabels() == null)
                continue;

            Map<String, String> podLabels = pod.getMetadata().getLabels();
            boolean match = true;

            for (Map.Entry<String, String> entry : labels.entrySet()) {
                if (!entry.getValue().equals(podLabels.get(entry.getKey()))) {
                    match = false;
                    break;
                }
            }
            if (match) {
                result.addItemsItem(pod);
            }
        }
        return result;
    }

    // Accessors for caches if needed by generator
    public V1PersistentVolumeClaimList getPvcListCache() {
        return pvcListCache;
    }
}

