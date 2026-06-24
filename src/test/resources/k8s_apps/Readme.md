
This folder contains a collection of Kubernetes applications used for conversion tests. 
Each sample application has a dedicated folder to keep all resources, and main documentation.

Kubernetes sample applications available in this folder:

From Digital Ocean https://github.com/digitalocean/kubernetes-sample-apps
- [bookinfo-example](bookinfo-example/) - Deploy the [Bookinfo](https://istio.io/latest/docs/examples/bookinfo) sample application.

helm repo add istio https://istio-release.storage.googleapis.com/charts
helm repo update
helm install istio-base istio/base -n istio-system --set defaultRevision=default --create-namespace
helm install istiod istio/istiod -n istio-system --wait
