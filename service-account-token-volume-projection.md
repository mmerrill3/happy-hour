Creating Service Account Token Volume Projection
===

## Why do this?  And, a few notes

* service account tokens are not time bounded JWT tokens.
* service account tokens are not audience bound
* service account tokens are stored in secrets which may be accessible to other pods
* projected token volumes are handled by the kubelet and recycled every 24 hours or at the 80% of the expiration time mark for the tokens
* projected token volumes are for intended audiences
* the pod/application must handle the rotation of the projected token volumes
* the feature gate ServiceAccountTokenVolumeProjection controls this ability.  It is enabled by passing all of the following flags to the API server: 
    * **--service-account-issuer**
    * **--service-account-signing-key-file**
    * **--service-account-api-audiences**
* design for the feature is [here](https://github.com/kubernetes/community/blob/master/contributors/design-proposals/storage/svcacct-token-volume-source.md)


## Installing

I installed using kops 1.18.alpha.3.  I needed to create a cluster with the apiserver settings that enable the feature gate ServiceAccountTokenVolumeProjection.

So, this is the version of kops I used:

```
kops version
I0605 09:41:45.123027   87860 featureflag.go:152] FeatureFlag "DrainAndValidateRollingUpdate"=true
Version 1.18.0-alpha.3 (git-27aab12b2)
```

This is how I created my cluster:

```
kops create cluster --name k8s.mmerrilldev.com --topology private --node-count 3 --master-count 3 --networking weave --zones us-east-1c,us-east-1d,us-east-1e --bastion --cloud aws --ssh-public-key ~/.ssh/kube.pub
```


Then, update your cluster config to enable service account token projection.  From [here](https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/#service-account-token-volume-projection)

```
  kubeAPIServer:
    apiAudiences:
    - api
    - vault
    serviceAccountIssuer: kubernetes.default.svc
    serviceAccountKeyFile:
    - /srv/kubernetes/server.key
    serviceAccountSigningKeyFile: /srv/kubernetes/server.key
```


Then, create your cluster:

```
kops update cluster --name k8s.mmerrilldev.com --yes
```

Check you cluster is ready:

```
kubectl cluster-info
Kubernetes master is running at https://api.k8s.mmerrilldev.com
KubeDNS is running at https://api.k8s.mmerrilldev.com/api/v1/namespaces/kube-system/services/kube-dns:dns/proxy

To further debug and diagnose cluster problems, use 'kubectl cluster-info dump'.
```


## Creating a service account

Let's create our namespace for testing

```
kubectl create namespace test
```

Now, let's create a service account for our worflow to use.  This is not the projected volume, but rather just a normal service account.

```
kubectl create sa -n test nginx
```

Ok, let's create the secondary service account, where it is a volume projected on a new pod

```
apiVersion: v1
kind: Pod
metadata:
  name: nginx
  namespace: test
spec:
  containers:
  - image: nginx
    name: nginx
    volumeMounts:
    - mountPath: /var/run/secrets/tokens
      name: vault-token
  serviceAccountName: nginx
  volumes:
  - name: vault-token
    projected:
      sources:
      - serviceAccountToken:
          path: vault-token
          expirationSeconds: 7200
          audience: vault
```


Now, let's create an admin service account so we can validate our tokens with the API

```
apiVersion: v1
kind: ServiceAccount
metadata:
  name: admin-reviewer
  namespace: test
---
apiVersion: rbac.authorization.k8s.io/v1beta1
kind: ClusterRoleBinding
metadata:
  name: role-tokenreview-binding
  namespace: test
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: system:auth-delegator
subjects:
- kind: ServiceAccount
  name: admin-reviewer
  namespace: test
```

Grab the JWT token for the admin-reviewer so we can use it to validate the projected tokens


Let's look at the token we projected:

```
kubectl exec -ti nginx -- cat /var/run/secrets/tokens/vault-token
```

Paste the token into jwt.io

Next, let's validate the token with the k8s API.

```
TOKEN=$(kubectl exec -ti nginx -- cat /var/run/secrets/tokens/vault-token)

ADMIN_TOKEN=$(kubectl get sa admin-reviewer -o jsonpath="{.secrets[0].name}"|xargs kubectl get secrets -o jsonpath="{.data.token}"|base64 -D)


curl -vvv --insecure -X "POST" "https://api.k8s.mmerrilldev.com/apis/authentication.k8s.io/v1/tokenreviews" \
     -H "Authorization: Bearer $ADMIN_TOKEN" \
     -H 'Content-Type: application/json; charset=utf-8' \
     -d $"{
  \"kind\": \"TokenReview\",
  \"apiVersion\": \"authentication.k8s.io/v1\",
  \"spec\": {
    \"token\": \"$TOKEN\"
  }
}"
```


We will see the auth token is valid:

```
{
  "kind": "TokenReview",
  "apiVersion": "authentication.k8s.io/v1",
  "metadata": {
    "creationTimestamp": null,
    "managedFields": [
      {
        "manager": "curl",
        "operation": "Update",
        "apiVersion": "authentication.k8s.io/v1",
        "time": "2020-06-05T16:06:53Z",
        "fieldsType": "FieldsV1",
        "fieldsV1": {"f:spec":{"f:token":{}}}
      }
    ]
  },
  "spec": {
    "token": "eyJhbGciOiJSUzI1NiIsImtpZCI6Im5MZDZuVDQzQ1ZkQ09qVS1ON3JSenJQVV92TXFXaHNiSEtDZjVuTUN4MjAifQ.eyJhdWQiOlsidmF1bHQiXSwiZXhwIjoxNTkxMzc5NTI1LCJpYXQiOjE1OTEzNzIzMjUsImlzcyI6Imt1YmVybmV0ZXMuZGVmYXVsdC5zdmMiLCJrdWJlcm5ldGVzLmlvIjp7Im5hbWVzcGFjZSI6InRlc3QiLCJwb2QiOnsibmFtZSI6Im5naW54IiwidWlkIjoiZDBkMGY2ZDEtODFiMi00MjQ2LWIxOTUtMjYzN2M4MTY1MjE3In0sInNlcnZpY2VhY2NvdW50Ijp7Im5hbWUiOiJuZ2lueCIsInVpZCI6Ijc4NjUzYWIzLTY1NjYtNDM3NC04ZmQxLTE1ZWRjNzBiZmRhNyJ9fSwibmJmIjoxNTkxMzcyMzI1LCJzdWIiOiJzeXN0ZW06c2VydmljZWFjY291bnQ6dGVzdDpuZ2lueCJ9.f4p3VD3fRCR7ph6st6Mbhj4qaDacuYFWDt6qQm2Wg3pU4c12H9Y28zw8g7VS1vPVKF0Fi4DFeWCJwxKYEnfJ9rKL2nLPhxgvFseGMajeebfFPtfO6zZZA2UudUuVO3VpREw6SCexHO-lg1HlCoJqlH9lKeWNTcp4QeoUr0dTwib3a9PiiZoPvv7OrAUCnZxnvO5w9vcrw7sp2Kcldw0yygade7RPPYex_AWqUcQzlq7tMxrghW98xEDQAXYDxc4QTFpJNWmdyiw-94WeK7NZqVfiVzW5KTjTo3i6LmJK0o1sEN1xOKhMZqC0YzliW4a6MAIALJWvfEJ63ssLpsPIMw"
  },
  "status": {
    "authenticated": true,
    "user": {
      "username": "system:serviceaccount:test:nginx",
      "uid": "78653ab3-6566-4374-8fd1-15edc70bfda7",
      "groups": [
        "system:serviceaccounts",
        "system:serviceaccounts:test",
        "system:authenticated"
      ],
      "extra": {
        "authentication.kubernetes.io/pod-name": [
  "nginx"
],
        "authentication.kubernetes.io/pod-uid": [
  "d0d0f6d1-81b2-4246-b195-2637c8165217"
]
      }
    },
    "audiences": [
      "vault"
    ]
  }
```




