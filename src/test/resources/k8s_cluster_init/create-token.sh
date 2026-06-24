#! /bin/bash
# create a K8s token

# conf
export CTX=rancher-desktop

# create permission
kubectl --context $CTX apply -f permissions.yaml

# display token
kubectl --context $CTX -n default describe secret $(kubectl --context $CTX -n default get secret | grep k8s-archi-converter- | awk '{print $1}')| grep token: