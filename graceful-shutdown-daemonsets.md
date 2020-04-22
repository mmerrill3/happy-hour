Graceful Daemonset Termination
===

## Installing

I installed using kops 1.16.  

So, this is the version of kops I used:

```
kops version
Version 1.16.0-alpha.2 (git-dfaf0b633)
```

This is how I created my cluster:

```
kops create cluster --name k8s.mmerrilldev.com --topology private --node-count 3 --master-count 3 --networking weave --zones us-east-1c,us-east-1d,us-east-1e --bastion --cloud aws --ssh-public-key ~/.ssh/kube.pub
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

Let's put our label on all of our nodes:

```
kubectl label node --all weave=true
```


Let's update weave's daemonset to apply a node selector (this will cause a rolling deployment!!!):

```
kubectl patch ds -n kube-system weave-net -p '{"spec":{"nodeSelector":{"weave":"true"}}}'
```

Let's cordon and drain a node now:

```
kubectl cordon <NODNAME>
kubectl drain --force --ignore-daemonsets=true --grace-period=10 --delete-local-data=true <NODENAME>
```

Now, let's remove the label from the node

```
kubectl label node <NODENAME> weave-
```

And, now we can safely remove the node from k8s

```
kubectl delete node <NODENAME>
```
