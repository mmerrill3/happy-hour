# Kubernetes RBAC

## Installing

I installed using kops 1.18.

So, this is the version of kops I used:

```
kops version
I0924 07:32:35.303332   25095 featureflag.go:154] FeatureFlag "DrainAndValidateRollingUpdate"=true
Version 1.18.0
```

This is how I created my cluster:

```
kops create cluster --name k8s.mmerrilldev.com --topology private --node-count 3 --master-count 3 --networking weave --zones us-east-1c,us-east-1d,us-east-1e --bastion --cloud aws --ssh-public-key ~/.ssh/kube.pub
```

## Description

Roll Based Access Control documentation [here](https://kubernetes.io/docs/reference/access-authn-authz/rbac/)

Roll Based Access Control consists of giving your workloads access to only what they need with the kubernetes API.

RBAC consists of the following concepts

- namespaces
- service accounts
- roles
- role bindings
- cluster roles
- cluster role bindings
- aggregrated roles
    - See [previous happy hour on aggegrated roles](https://www.youtube.com/watch?v=tVqsGjdTMUI)


Best Practice: Zero Trust
By default, give your workloads no permission to the kubernetes API.  If a pod is compromised, the control plane will not be accessible to the attacker.
Kubernetes default tokens have the same permissions as the unauthenticated API user in kubernetes.

## namespaces

Namespaces are a way to logically group your workloads, and apply security and scheduling rules specific for that grouping.

For instance, we can say that a namespace cannot consume more than 20 CPUs.  Or, a namespace cannot consume more the 256 GB of memory.

Also, we can list the users that have access to a namespace.  Those users are represented via a token issued either by the kubernetes API, or by an external OIDC provider (google OIDC or IAM with OIDC integration).  Moreover, those users can be given a role, and their permissions can be managed at the resource and verb level when using the kubernetes RESTful API.

The roles given out can be specific for a namespace (simply a role), or, they can be applied across all namespaces (a cluster role).

Typically, you will not need a cluster role, since there are many predefined cluster roles that ship with kubernetes that are available for re-use.  Additionally, applications wouldn't normally need access to the kubernetes API for business logic.

Out of the box, kubernetes ships with a default namespace, and a kube-system namespace.  Pods in the kube-system namespace are critical pods, and have the highest priority.



## Demo

* Setup k8s cluster (I used kops verison 1.18)
* Create a test namespace

```
kubectl create namespace test
```

* Launch a bitnami/kubectl pod with default RBAC token.

```
kubectl run -ti -n test --rm --requests=cpu=100m,memory=256Mi --limits=cpu=100m,memory=256Mi test-kubectl --image bitnami/kubectl --command -- sh
```
* Show where the default token is placed by kubelet

```
cat /var/run/secrets/kubernetes.io/serviceaccount/token
```

* Take the token to https://jwt.io/, and see what it contains
* Use the default token to access the kubernetes API.
    * From the kubectl pod, show that access is denied by default

```
$ kubectl get pods -n test
Error from server (Forbidden): pods is forbidden: User "system:serviceaccount:test:default" cannot list resource "pods" in API group "" in the namespace "test"
```
* Ok, its denied, now what?  Let's create a service account

```
mmerrillmbp:helm mmerrill$ kubectl create sa -n test mike
serviceaccount/mike created
mmerrillmbp:helm mmerrill$
```

* Create another workflow, with the new service account

```
kubectl run -ti -n test --rm --requests=cpu=100m,memory=256Mi --limits=cpu=100m,memory=256Mi test-kubectl --image bitnami/kubectl --command --serviceaccount mike -- sh
```

* See our new service account token, grab it and verify it at https://jwt.io/

```
cat /var/run/secrets/kubernetes.io/serviceaccount/token
```


* Try to use the new token to access the kubernetes API.

```
kubectl get pod
Error from server (Forbidden): pods is forbidden: User "system:serviceaccount:test:mike" cannot list resource "pods" in API group "" in the namespace "test"
```

* What happened, how do we fix this?  Let's use an existing role, and then bind the role to this user

```
cat <<EOF | kubectl apply -f -
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: test-rolebinding
  namespace: test
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: admin
subjects:
  - kind: ServiceAccount
    name: mike
    namespace: test
EOF
rolebinding.rbac.authorization.k8s.io/test-rolebinding created
```

* Now we see it!

```
$ kubectl get pod
NAME           READY   STATUS    RESTARTS   AGE
test-kubectl   1/1     Running   0          7m55s
```

* But, wait, what else can we do?  Uh oh...

```
$ kubectl run -ti -n test --rm --requests=cpu=100m,memory=256Mi --limits=cpu=100m,memory=256Mi test2-kubectl --image bitnami/kubectl --command --serviceaccount mike -- sh
If you don't see a command prompt, try pressing enter.

$ exit
Session ended, resume using 'kubectl attach test2-kubectl -c test2-kubectl -i -t' command when the pod is running
pod "test2-kubectl" deleted
```

* We've escaped from our container, and we are running anything we want, anywhere.  We are the admin.  We can also create other RBAC policies to give to our new pods, since we are the admin!

* Lock it down!  Change the rolebinding to view!

```
cat <<EOF | kubectl apply --force -f -
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: test-rolebinding
  namespace: test
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: view
subjects:
  - kind: ServiceAccount
    name: mike
    namespace: test
EOF
rolebinding.rbac.authorization.k8s.io/test-rolebinding configured
```

* Now, try it out.  We can't create workloads, but we can see resources with view

```
$ kubectl run -ti -n test --rm --requests=cpu=100m,memory=256Mi --limits=cpu=100m,memory=256Mi test-kubectl --image bitnami/kubectl --command --serviceaccount mike -- sh
Error from server (Forbidden): pods is forbidden: User "system:serviceaccount:test:mike" cannot create resource "pods" in API group "" in the namespace "test"
$ kubectl get pod
NAME           READY   STATUS    RESTARTS   AGE
test-kubectl   1/1     Running   0          13m
```


## roles

- This is how we lump together permissions.  This is really the RBAC policy
- Permissions can be down to the sub resource level, and API verb level.

## binding users to a role, the role binding

- This is where we apply the policy to service accounts, or even individual users.

## default cluster roles

- Kubernetes has some default cluster roles, like view, edit, and admin.
- Roles that start with system: are usually specialized for controllers running inside the control plane
- You can apply a cluster role to a namespace user or service token.  We did above with view and admin

```
kubectl get clusterroles
```


## command line RBAC

- kubectl auth can-i
    - add the --list argument to see a detail list of permissions

- access the openAPI documents for kubernetes
    - start the proxy so you're local token is used
    - access the API server's /openapi/v2 endpoint


