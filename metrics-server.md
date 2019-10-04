# Metrics service site
https://github.com/kubernetes-incubator/metrics-server

## Metrics service md site

checkout the project, then look in deploy/1.8+ directory.

kubectl create -f deploy/1.8+/


## Metrics service gotchas
* By default, the self signed cert on metrics service won't be acceptable

See:
> E1004 15:45:12.363261       1 manager.go:111] unable to fully collect metrics: [unable to fully scrape metrics from source kubelet_summary:ip-172-20-40-132.ec2.internal: unable to fetch metrics from Kubelet ip-172-20-40-132.ec2.internal (ip-172-20-40-132.ec2.internal): Get https://ip-172-20-40-132.ec2.internal:10250/stats/summary?only_cpu_and_memory=true: x509: certificate signed by unknown authority, unable to fully scrape metrics from source kubelet_summary:ip-172-20-39-210.ec2.internal: unable to fetch metrics from Kubelet ip-172-20-39-210.ec2.internal (ip-172-20-39-210.ec2.internal): Get https://ip-172-20-39-210.ec2.internal:10250/stats/summary?only_cpu_and_memory=true: x509: certificate signed by unknown authority, unable to fully scrape metrics from source kubelet_summary:ip-172-20-34-17.ec2.internal: unable to fetch metrics from Kubelet ip-172-20-34-17.ec2.internal (ip-172-20-34-17.ec2.internal): Get https://ip-172-20-34-17.ec2.internal:10250/stats/summary?only_cpu_and_memory=true: x509: certificate signed by unknown authority, unable to fully scrape metrics from source kubelet_summary:ip-172-20-46-84.ec2.internal: unable to fetch metrics from Kubelet ip-172-20-46-84.ec2.internal (ip-172-20-46-84.ec2.internal): Get https://ip-172-20-46-84.ec2.internal:10250/stats/summary?only_cpu_and_memory=true: x509: certificate signed by unknown authority]


* Anonymous access to the kubelet is turned off, need to enable token verification on the kubelet .... OR
    * Turn off by adding the following the the deployment of metrics-server
    
>     - command:
>         - /metrics-server
>         - --cert-dir=/tmp
>         - --logtostderr
>         - --deprecated-kubelet-completely-insecure
>         - --kubelet-port=10255
>         - --kubelet-preferred-address-types=InternalIP
        
* Run kubectl top node to see node metrics
* Run kubectl top pod to see pod metrics in the current namespace
* See the metrics from the API itself, kubectl get --raw "/apis/metrics.k8s.io/v1beta1/pods" or kubectl get --raw "/apis/metrics.k8s.io/v1beta1/nodes"

## Security Issues 

* By default, we are serving read only data from our kubeletes on port 10255 (curl -vvv http://<IP>:10255/stats/summary?only_cpu_and_memory=true)
* We can see what is running, and other data, allowing an attacker to get a starting point for what to attack, and to see what is interesting.
* You can set the default http port to 0 to turn off the http service, ... but ... you need to allow access to the clients of kubelet's aggregate APIs.
    * Make sure the CA for kubelet is the same CA for the client service accounts that will call kubelet
    * Enable authentication.k8s.io/v1beta1 on the API server via the runtime config parameters
    * Service account needs RBAC permissions to the kubelet APIs
    * Kubelet needs some extra parameters to call the API server to perform the authentication:
        
        > --authentication-token-webhook=true
        > --extra-config=kubelet.authorization-mode=Webhook
