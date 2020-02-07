Ephemeral Containers
===

## Installing

I installed using kops 1.16.  I needed to create a cluster with the feature gate EphemeralContainers enabled.

So, this is the version of kops I used:

```
kops version
Version 1.16.0-alpha.2 (git-dfaf0b633)
```

This is how I created my cluster:

```
kops create cluster --name k8s.mmerrilldev.com --topology private --node-count 3 --master-count 3 --networking weave --zones us-east-1c,us-east-1d,us-east-1e --bastion --cloud aws --ssh-public-key ~/.ssh/kube.pub
```

Then, update your cluster config to add the feature gate for ephemeral containers:

```
  kubeAPIServer:
    featureGates:
      EphemeralContainers: "true"
  kubeControllerManager:
    featureGates:
      EphemeralContainers: "true"
  kubeScheduler:
    featureGates:
      EphemeralContainers: "true"
  kubelet:
    featureGates:
      EphemeralContainers: "true"
```
      
Then, create your cluster:

```
kops update cluster --name k8s.mmerrilldev.com --yes
```

Check you cluster is ready:

```
mmerrillmbp:.kube mmerrill$ kubectl cluster-info
Kubernetes master is running at https://api.k8s.mmerrilldev.com
KubeDNS is running at https://api.k8s.mmerrilldev.com/api/v1/namespaces/kube-system/services/kube-dns:dns/proxy

To further debug and diagnose cluster problems, use 'kubectl cluster-info dump'.
```

## Running the pod

Now, let's run a basic nginx pod:

```
kubectl run nginx --image=nginx
kubectl run --generator=deployment/apps.v1 is DEPRECATED and will be removed in a future version. Use kubectl run --generator=run-pod/v1 or kubectl create instead.
deployment.apps/nginx created
```

Change nginx's deployment's pod spec for process namespace sharing:


```
    spec:
      shareProcessNamespace: true
```
         
Apply this, and nginx will restart with the shareProcessNamespace attribute enabled.
      
Change the ephemeral section to this:
      
 ```     
    {
    "apiVersion": "v1",
    "kind": "EphemeralContainers",
    "metadata": {
            "name": "nginx"
    },
    "ephemeralContainers": [{
        "command": [
            "sh"
        ],
        "image": "mmerrill35/tinytools:latest",
        "imagePullPolicy": "Always",
        "name": "debugger",
        "stdin": true,
        "tty": true,
        "terminationMessagePolicy": "File"
    }]
}
```

Save this to a file called ec.json.  Now, let's apply it:

```
mmerrillmbp:.kube mmerrill$ kubectl replace --raw /api/v1/namespaces/testing/pods/nginx-dc76f9f48-tj5qq/ephemeralcontainers  -f ec.json
{"kind":"EphemeralContainers","apiVersion":"v1","metadata":{"name":"nginx-dc76f9f48-tj5qq","namespace":"testing","selfLink":"/api/v1/namespaces/testing/pods/nginx-dc76f9f48-tj5qq/ephemeralcontainers","uid":"a2d19274-648f-4c64-864b-b618eb2baf66","resourceVersion":"9893","creationTimestamp":"2020-02-07T16:27:53Z"},"ephemeralContainers":[{"name":"debugger","image":"mmerrill35/tinytools:latest","command":["sh"],"resources":{},"terminationMessagePolicy":"File","imagePullPolicy":"Always","stdin":true,"tty":true}]}
```

If you do a describe on the pod, and you see the ephemeral containing running, you are good.
We ran into an issue initially because the scheduler did not have the feature gate enabled, so the ephemeral container did not start.


Describe your pod to see it:

```
mmerrillmbp:.kube mmerrill$ kubectl describe pod nginx-dc76f9f48-tj5qq
Name:         nginx-dc76f9f48-tj5qq
Namespace:    testing
Priority:     0
Node:         ip-172-20-88-33.ec2.internal/172.20.88.33
Start Time:   Fri, 07 Feb 2020 11:27:53 -0500
Labels:       pod-template-hash=dc76f9f48
              run=nginx
Annotations:  <none>
Status:       Running
IP:           100.100.0.1
IPs:
  IP:           100.100.0.1
Controlled By:  ReplicaSet/nginx-dc76f9f48
Containers:
  nginx:
    Container ID:   docker://255773b5567afe2bc76fd5c594bc4fbd59ce7835b89d798647b6c3fd64d8271d
    Image:          nginx
    Image ID:       docker-pullable://nginx@sha256:ad5552c786f128e389a0263104ae39f3d3c7895579d45ae716f528185b36bc6f
    Port:           <none>
    Host Port:      <none>
    State:          Running
      Started:      Fri, 07 Feb 2020 11:27:58 -0500
    Ready:          True
    Restart Count:  0
    Environment:    <none>
    Mounts:
      /var/run/secrets/kubernetes.io/serviceaccount from default-token-q7fmn (ro)
Ephemeral Containers:
  debugger:
    Image:      mmerrill35/tinytools:latest
    Port:       <none>
    Host Port:  <none>
    Command:
      sh
    Environment:  <none>
    Mounts:       <none>
Conditions:
  Type              Status
  Initialized       True 
  Ready             True 
  ContainersReady   True 
  PodScheduled      True 
Volumes:
  default-token-q7fmn:
    Type:        Secret (a volume populated by a Secret)
    SecretName:  default-token-q7fmn
    Optional:    false
QoS Class:       BestEffort
Node-Selectors:  <none>
Tolerations:     node.kubernetes.io/not-ready:NoExecute for 300s
                 node.kubernetes.io/unreachable:NoExecute for 300s
Events:
  Type    Reason     Age   From                                   Message
  ----    ------     ----  ----                                   -------
  Normal  Scheduled  39m   default-scheduler                      Successfully assigned testing/nginx-dc76f9f48-tj5qq to ip-172-20-88-33.ec2.internal
  Normal  Pulling    39m   kubelet, ip-172-20-88-33.ec2.internal  Pulling image "nginx"
  Normal  Pulled     39m   kubelet, ip-172-20-88-33.ec2.internal  Successfully pulled image "nginx"
  Normal  Created    39m   kubelet, ip-172-20-88-33.ec2.internal  Created container nginx
  Normal  Started    39m   kubelet, ip-172-20-88-33.ec2.internal  Started container nginx
```
  
Now, check to see the ephemeral
  
```
kubectl attach -ti nginx-dc76f9f48-tj5qq -c debugger
```

## Additional Info

The kubectl debug command will let you automatically create and attach to an ephemeral container:
[link](https://github.com/kubernetes/kubernetes/issues/45922)

Ephemeral Containers [link](https://kubernetes.io/docs/concepts/workloads/pods/ephemeral-containers/)

