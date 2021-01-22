Setting up a docker mirror with OSS Nexus
===

## Intro, what are we trying to do here?

* we are trying to alleviate the docker API rate limits : https://docs.docker.com/docker-hub/download-rate-limit/
* we are introducing cert-manager, nginx ingress controller, external-dns, nexus, and how to configure a k8s cluster to use these pieces.


## Installing our k8s cluster with docker mirror configured

I installed using kOps.  I'm using kOps because it allows us to configure the docker daemon at runtime.

So, this is the version of kOps I used:

```
 ~/kops/kops-darwin-amd64 version
I0122 09:11:50.916006   17160 featureflag.go:167] FeatureFlag "DrainAndValidateRollingUpdate"=true
Version 1.19.0-beta.3 (git-e43f1cc6e3c77d093935d1706042861095d75eb7)
```

This is how I created my cluster:

```
~/kops/kops-darwin-amd64 create cluster --name k8s.mmerrilldev.com --topology private --node-count 3 --master-count 1 --networking weave --zones us-east-1c --bastion --cloud aws --ssh-public-key ~/.ssh/kube.pub
```

Let's create the section that sets up the docker mirror for the docker daemon, and add this to the cluster spec:

```
spec:
  docker:
    registryMirrors:
    - https://docker-mirror.mmerrilldev.com
```

Ok, now let's create the mirror.

We need a valid certificate and nexus... the actual repository.  So, we'll use external-dns to publish our hosts to route53, nginx to create an ingress so we use the certificate, and nexus to store the cache and act as a docker repository.

## Setup IAM policy for our workers

Note that this is an aggregation of all the permissions needed for external-dns and cert-manager.  You should not do this in production, but rather have more granular roles.  This demo is just reusing the worker nodes' default EC2 IAM role.  Anyways, for demo purposes, this is the policy:

```
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "VisualEditor0",
            "Effect": "Allow",
            "Action": [
                "ec2:DescribeInstances",
                "ecr:GetDownloadUrlForLayer",
                "ec2:DescribeRegions",
                "ecr:BatchGetImage",
                "ecr:GetAuthorizationToken",
                "ecr:DescribeRepositories",
                "ecr:ListImages",
                "ecr:BatchCheckLayerAvailability",
                "ecr:GetRepositoryPolicy"
            ],
            "Resource": "*"
        },
        {
            "Sid": "VisualEditor1",
            "Effect": "Allow",
            "Action": [
                "s3:GetEncryptionConfiguration",
                "s3:ListBucketVersions",
                "s3:ListBucket",
                "s3:GetBucketLocation"
            ],
            "Resource": "arn:aws:s3:::k8s.mmerrilldev.com"
        },
        {
            "Sid": "VisualEditor2",
            "Effect": "Allow",
            "Action": "s3:Get*",
            "Resource": [
                "arn:aws:s3:::k8s.mmerrilldev.com/k8s.mmerrilldev.com/addons/*",
                "arn:aws:s3:::k8s.mmerrilldev.com/k8s.mmerrilldev.com/cluster.spec",
                "arn:aws:s3:::k8s.mmerrilldev.com/k8s.mmerrilldev.com/config",
                "arn:aws:s3:::k8s.mmerrilldev.com/k8s.mmerrilldev.com/instancegroup/*",
                "arn:aws:s3:::k8s.mmerrilldev.com/k8s.mmerrilldev.com/pki/issued/*",
                "arn:aws:s3:::k8s.mmerrilldev.com/k8s.mmerrilldev.com/pki/ssh/*",
                "arn:aws:s3:::k8s.mmerrilldev.com/k8s.mmerrilldev.com/secrets/dockerconfig"
            ]
        },
        {
            "Effect": "Allow",
            "Action": "route53:GetChange",
            "Resource": "arn:aws:route53:::change/*"
        },
        {
            "Effect": "Allow",
            "Action": [
                "route53:ChangeResourceRecordSets",
                "route53:ListResourceRecordSets"
            ],
            "Resource": "arn:aws:route53:::hostedzone/*"
        },
        {
            "Effect": "Allow",
            "Action": [
                "route53:ListHostedZonesByName",
                "route53:ListHostedZones",
                "route53:ListResourceRecordSets"
            ],
            "Resource": "*"
        }
    ]
}
```

## Install cert-manager so we can have a TLS docker mirror

 * Docker needs a TLS endpoint.  So, we'll need to set one up.
 * I'll use let's encrypt to create my certificate
 * Install the CRDs and helm chart for cert-manager first.

```
# Install the CustomResourceDefinition resources separately
kubectl apply -f https://github.com/jetstack/cert-manager/releases/download/v1.1.0/cert-manager.crds.yaml
# Create the namespace for cert-manager
kubectl create namespace cert-manager
# Label the cert-manager namespace to disable resource validation
kubectl label namespace cert-manager certmanager.k8s.io/disable-validation=true
helm repo add jetstack https://charts.jetstack.io
helm repo update
helm install cert-manager jetstack/cert-manager --namespace cert-manager --version v1.1.0
```
 
 * Install the issuers.  Save this issuers file, replace with your hosted zone in AWS

```
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-staging
spec:
  acme:
    email: <YOUR EMAIL>
    server: https://acme-staging-v02.api.letsencrypt.org/directory
    privateKeySecretRef:
      name: cert-manager-secret
    solvers:
    - dns01:
        route53:
          region: us-east-1
          secretAccessKeySecretRef:
            name: ""
      selector: {}
---
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-prod-cluster-issuer
spec:
  acme:
    email: <YOUR EMAIL>
    privateKeySecretRef:
      key: ""
      name: letsencrypt-prod
    server: https://acme-v02.api.letsencrypt.org/directory
    solvers:
    - selector: {}
      dns01:
        route53:
          region: us-east-1
          hostedZoneID: <FILLMEIN>
          secretAccessKeySecretRef:
            name: ""
```
 * Install the certificates.  One for nexus itself, and one for the docker-mirror.
 
```
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: docker-mirror
  namespace: kube-system
spec:
  dnsNames:
    - docker-mirror.mmerrilldev.com
  secretName: docker-mirror-cert-tls
  issuerRef:
    name: letsencrypt-prod-cluster-issuer
    kind: ClusterIssuer

---
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: nexus
  namespace: kube-system
spec:
  dnsNames:
    - nexus.mmerrilldev.com
  secretName: nexus-cert-tls
  issuerRef:
    name: letsencrypt-prod-cluster-issuer
    kind: ClusterIssuer
```

 
 
## Install external-dns to publish ingresses to route53

* We need something to push our hosts into route53 from our ingresses.  This is so we can access nexus and the docker-mirror through the load balancer that will be created by the nginx ingress controller.

```
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update
helm install external-dns bitnami/external-dns
```

## Install nginx ingress controller

 * We need an ingress controller to handle TLS and inbound traffic
 
```
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
helm repo update
helm install ingress-nginx ingress-nginx/ingress-nginx
```
 
## Install nexus OSS repository manager

 * Run the helm chart from https://artifacthub.io/packages/helm/oteemo-charts/sonatype-nexus.
 * I downloaded the chart first, then set a couple settings.
   * enable ingresses
   * set the nexus host and docker host
   * disable the proxy, since we are using an ingress controller
   
 These are the sections I changed.  Note how the host names match our certificates from above! :
 
```
nexusProxy:
  enabled: false
  env:
    nexusDockerHost: docker-mirror.mmerrilldev.com
    nexusHttpHost: nexus.mmerrilldev.com
ingress:
  enabled: true
  path: /
  labels: {}
  annotations:
    kubernetes.io/ingress.class: nginx
    nginx.ingress.kubernetes.io/proxy-body-size: 128m
  tls:
    enabled: true
    secretName: nexus-cert-tls
  rules:

ingressDocker:
  enabled: true
  path: /
  labels: {}
  annotations:
    kubernetes.io/ingress.class: nginx
    nginx.ingress.kubernetes.io/proxy-body-size: 128m
  tls:
    enabled: true
    secretName: docker-mirror-cert-tls
  rules:
```

 * Setup the docker proxy, and docker group in the nexus repo
 * Add the docker bearer token in the security realm
 * Allow anonymous access, for this demo only.
 
## Here is our docker daemon with the registry running
```
ubuntu@ip-172-20-60-149:~$ sudo su
root@ip-172-20-60-149:/home/ubuntu# ps -ef|grep docker
root        5732       1  1 15:09 ?        00:01:27 /usr/bin/dockerd -H fd:// --ip-masq=false --iptables=false --log-driver=json-file --log-level=info --log-opt=max-file=5 --log-opt=max-size=10m --registry-mirror=https://docker-mirror.mmerrilldev.com --storage-driver=overlay2
```


## Download an image

Execute this command to run nginx

```
kubectl run --image nginx:latest mike
```

Now, let's see if its in our nexus cache!

```
mmerrillmbp:helm mmerrill$ kubectl run --image nginx:latest mike
pod/mike created
mmerrillmbp:helm mmerrill$ kubectl describe po mke
Error from server (NotFound): pods "mke" not found
mmerrillmbp:helm mmerrill$ kubectl describe po mike
Name:         mike
Namespace:    kube-system
Priority:     0
Node:         ip-172-20-60-149.ec2.internal/172.20.60.149
Start Time:   Fri, 22 Jan 2021 12:40:34 -0500
Labels:       run=mike
Annotations:  <none>
Status:       Running
IP:           100.104.0.3
IPs:
  IP:  100.104.0.3
Containers:
  mike:
    Container ID:   docker://df0a73435590664f28cf4c762996e3fdb9bf9a0f24df0246da615a7ef2160c1f
    Image:          nginx:latest
    Image ID:       docker-pullable://nginx@sha256:10b8cc432d56da8b61b070f4c7d2543a9ed17c2b23010b43af434fd40e2ca4aa
    Port:           <none>
    Host Port:      <none>
    State:          Running
      Started:      Fri, 22 Jan 2021 12:40:41 -0500
    Ready:          True
    Restart Count:  0
    Environment:    <none>
    Mounts:
      /var/run/secrets/kubernetes.io/serviceaccount from default-token-pxqt5 (ro)
Conditions:
  Type              Status
  Initialized       True 
  Ready             True 
  ContainersReady   True 
  PodScheduled      True 
Volumes:
  default-token-pxqt5:
    Type:        Secret (a volume populated by a Secret)
    SecretName:  default-token-pxqt5
    Optional:    false
QoS Class:       BestEffort
Node-Selectors:  <none>
Tolerations:     node.kubernetes.io/not-ready:NoExecute op=Exists for 300s
                 node.kubernetes.io/unreachable:NoExecute op=Exists for 300s
Events:
  Type    Reason     Age   From               Message
  ----    ------     ----  ----               -------
  Normal  Scheduled  9s    default-scheduler  Successfully assigned kube-system/mike to ip-172-20-60-149.ec2.internal
  Normal  Pulling    8s    kubelet            Pulling image "nginx:latest"
  Normal  Pulled     3s    kubelet            Successfully pulled image "nginx:latest" in 4.899256642s
  Normal  Created    2s    kubelet            Created container mike
  Normal  Started    2s    kubelet            Started container mike
```



  