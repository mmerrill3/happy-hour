Examining IRSA (IAM Roles for Kubernetes Service Accounts) and re-examining Service Account Token Volume Projection
===

## Intro, what are we trying to do here?

* this is related to my previous demonstration from last year on projected service account tokens
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

## AWS tie-in
* AWS has created a mutating web hook admission controller to automate IRSA.  See: https://github.com/aws/amazon-eks-pod-identity-webhook.
    * Listens for creation of new pods in your cluster.  It needs RBAC permissions for this.
    * It will look for annotations on service accounts for the pods to determine which IAM role should be used in the JWT token.
    * If there is an annotation, it will tell the API server to sign JWT tokens using the OIDC keys we are going to make below.  The JWT token will 
* AWS has extended STS to allow for publicly available OIDC keys to be trusted, and tied to IAM roles.
* The oidc provider must be trusted by STS on the AWS account.
* OIDC providers can be reused across multiple AWS EKS clusters or accounts, but clusters cannot use more than one OIDC provider.
* AWS has updates all of their SDKs to look for this new authentication flow, called the WebIdentityTokenCredentialsProvider.
    *Environment variables AWS_REGION, AWS_WEB_IDENTITY_TOKEN_FILE, and AWS_ROLE_ARN are used by the SDK to control the flow
    *This flow is higher in the authorization chain now to be used before EC2 meta data authorization flows with AWS STS.
* Now, we don't need to override EC2 meta data work flows with tools like kiam.  IAM identities are projected onto pods during pod creation.
* Also, IAM roles can be scoped to individual namespaced service accounts, or the k8s namespace, level.
* The projected JWT token will look like this

```
{
  "aud": [
    "sts.amazonaws.com"
  ],
  "exp": 1610422514,
  "iat": 1610336114,
  "iss": "https://s3.amazonaws.com/auth.k8s.mmerrilldev.net",
  "kubernetes.io": {
    "namespace": "security",
    "pod": {
      "name": "test-cc4686b7b-lpcn2",
      "uid": "faf27115-d43a-43d6-b1c1-7ecc3ee98843"
    },
    "serviceaccount": {
      "name": "test",
      "uid": "719b6bf5-a897-48dd-ad05-3b5b6581c13a"
    }
  },
  "nbf": 1610336114,
  "sub": "system:serviceaccount:security:test"
}
```

## create a new key pair for use

We'll be doing this the hard way to understand this completely.  The recipe that we will be following is generally from here: https://github.com/aws/amazon-eks-pod-identity-webhook/blob/master/SELF_HOSTED_SETUP.md.  This is the key pair we will be using.  Don't make a password for the key pair, we'll keep this simple for now.  The page assumes you are operating from within the project https://github.com/mmerrill3/happy-hour.

bash scipt:

```
# Generate the keypair
PRIV_KEY="oidc/sa-signer.key"
PUB_KEY="oidc/sa-signer.key.pub"
PKCS_KEY="oidc/sa-signer-pkcs8.pub"
# Generate a key pair
ssh-keygen -t rsa -b 2048 -f $PRIV_KEY -m pem
# convert the SSH pubkey to PKCS8
ssh-keygen -e -m PKCS8 -f $PUB_KEY > $PKCS_KEY
```

These keys will need to be put on the API server so when the API Server issues JWT tokens, it will be using the keys above.  They are the OIDC issuer keys. Since the API server only takes one private key, we can only tie one kubernetes cluster to one OIDC provider.


## oidc issuer

We need to publish the keys for consumption by STS when verifying JWT tokens.  Let's use S3 for that, but it can really be any publicly hosted endpoint.

Execute this from the command line (this assume the aws cli is installed)

```
export S3_BUCKET="auth.k8s.mmerrilldev.net"
aws s3api create-bucket --bucket $S3_BUCKET > /dev/null
```

Next, let's create the keys.json file, and the OIDC contract (.well-known/openid-configuration file).

To create discovery file, run the following from the command line

```
cat <<EOF > oidc/discovery.json
{
    "issuer": "https://s3.amazonaws.com/auth.k8s.mmerrilldev.net/",
    "jwks_uri": "https://s3.amazonaws.com/auth.k8s.mmerrilldev.net/keys.json",
    "authorization_endpoint": "urn:kubernetes:programmatic_authorization",
    "response_types_supported": [
        "id_token"
    ],
    "subject_types_supported": [
        "public"
    ],
    "id_token_signing_alg_values_supported": [
        "RS256"
    ],
    "claims_supported": [
        "sub",
        "iss"
    ]
}
EOF
```

Publish this discovery file with public read only permissions as the OIDC configuration endpoint

```
aws s3 cp --acl public-read oidc/discovery.json s3://auth.k8s.mmerrilldev.net/.well-known/openid-configuration
```

Ok, now create the OIDC keys file.  We'll use the following go program to publish out the format for us: https://github.com/aws/amazon-eks-pod-identity-webhook/blob/master/hack/self-hosted/main.go

To run this utility, pass in the public keys we made above.  Below is how I ran it

```
wget https://raw.githubusercontent.com/aws/amazon-eks-pod-identity-webhook/master/hack/self-hosted/main.go
go run main.go -key oidc/sa-signer-pkcs8.pub  | jq '.keys += [.keys[0]] | .keys[1].kid = ""' > oidc/keys.json
```

Let's publish this keys.json file now, with public read only permissions as well

```bash
aws s3 cp --acl public-read oidc/keys.json s3://auth.k8s.mmerrilldev.net/keys.json
```

Great, our OIDC keys are setup, and are publicly available for AWS STS to consume.

## changes required within IAM on your account

Go to the console, and setup your OIDC provider within IAM.  You'll need the endpoint (in my case https://s3-us-east-1.amazonaws.com/auth.k8s.mmerrilldev.net).  IAM will figure out the thumbprint for the public certificate hosting the OIDC endpoint for you.

The audience will be sts.amazonaws.com.  This is critical.  It is the audience that we will configured the API server with, and it will be in the JWT tokens.

If you want to do this at the AWS CLI, you can follow this bash script

```bash
CA_THUMBPRINT=$(openssl s_client -connect s3.amazonaws.com:443 -servername s3.amazonaws.com \
  -showcerts < /dev/null 2>/dev/null | openssl x509 -in /dev/stdin -sha1 -noout -fingerprint | cut -d '=' -f 2 | tr -d ':')

aws iam create-open-id-connect-provider \
     --url https://s3.amazonaws.com/auth.k8s.mmerrilldev.net \
     --thumbprint-list $CA_THUMBPRINT \
     --client-id-list sts.amazonaws.com
```
 

## Create the IAM role

We can run this at the CLI.  We'll create a role called k8s-ecr, and give it read access to all ECR repos in our account.

The trust relationship (this is SUPER critical!) for this role will be setup to tie the k8s service account "test" in the namespace "security" to this role.  Apply this trust policy.  Substitute your AWS account id for the stars below

```
cat > iam/trust-policy.json << EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::565009088587:oidc-provider/s3.amazonaws.com/auth.k8s.mmerrilldev.net"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "s3.amazonaws.com/auth.k8s.mmerrilldev.net:sub": "system:serviceaccount:security:test"
        }
      }
    }
  ]
}
EOF

aws iam create-role --role-name k8s-ecr --assume-role-policy-document file://iam/trust-policy.json
aws iam attach-role-policy --role-name k8s-ecr --policy-arn arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly
```


## Installing our k8s cluster with OIDC JWT tokens enabled

I installed using kops.  I needed to create a cluster with the apiserver settings below that enable the feature gate ServiceAccountTokenVolumeProjection.

So, this is the version of kops I used:

```
 ~/kops/kops-darwin-amd64 version
I0111 11:58:44.090013   86302 featureflag.go:167] FeatureFlag "DrainAndValidateRollingUpdate"=true
Version 1.19.0-beta.2 (git-c006d97e5596024f9b9a5681c97300165156319e)
```

This is how I created my cluster:

```
kops create cluster --name k8s.mmerrilldev.com --topology private --node-count 3 --master-count 3 --networking weave --zones us-east-1c,us-east-1d,us-east-1f --bastion --cloud aws --ssh-public-key ~/.ssh/kube.pub
```


Then, update your cluster config to enable service account token projection, specifically with the keys we made above..  From [here](https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/#service-account-token-volume-projection)

First, create some file assets for new files to be placed on the masters.  Run  kops edit cluster <your cluster name> and add this to the spec:

```
spec:
  fileAssets:
  - content: |
      -----BEGIN PUBLIC KEY-----
      <PUBLIC KEY>
      -----END PUBLIC KEY-----
    name: sa-signer-pkcs8.pub
    path: /etc/kubernetes/pki/kube-apiserver/sa-signer-pkcs8.pub
    roles:
    - Master
  - content: |
      -----BEGIN RSA PRIVATE KEY-----
      <PRIVATE KEY>
      -----END RSA PRIVATE KEY-----
    name: sa-signer.key
    path: /etc/kubernetes/pki/kube-apiserver/sa-signer.key
    roles:
    - Master
```

Then, configure the API server to use these files.  Using kops, edit the cluster config and add this section to the kubeAPIServer section.

```
  kubeAPIServer:
    apiAudiences:
    - sts.amazonaws.com
    serviceAccountIssuer: https://s3.amazonaws.com/auth.k8s.mmerrilldev.net
    serviceAccountKeyFile:
    - /etc/kubernetes/pki/kube-apiserver/sa-signer-pkcs8.pub
    - /srv/kubernetes/server.key
    serviceAccountSigningKeyFile: /etc/kubernetes/pki/kube-apiserver/sa-signer.key
```

We need /srv/kubernetes/server.key, since we need to also trust service accounts signed by the controller manager.  This is really important, and shows how we can have multiple public keys for the API server to use to validate JWT tokens.


Then, create your cluster:

```
kops update cluster --name k8s.mmerrilldev.com --yes --admin
```

Check you cluster is ready:

```
kubectl cluster-info
Kubernetes master is running at https://api.k8s.mmerrilldev.com
KubeDNS is running at https://api.k8s.mmerrilldev.com/api/v1/namespaces/kube-system/services/kube-dns:dns/proxy

To further debug and diagnose cluster problems, use 'kubectl cluster-info dump'.
```


Connect to the cluster, and create our security namespace

Let's create our namespace for testing

```
kubectl create namespace security
```


## Deploy the mutating web hook admission controller

 * With golang 1.15, we must use a SAN certificate for the web hook.
 * By default, tokens are published to /var/run/secrets/eks.amazonaws.com/serviceaccount.  You can change the directory if you'd like.
 * Create the certificate for the controller.  We can leverage the deployment yamls from the irsa/webhook directory.  Run this script to configure the deployment with the certificate needed to start the pod.
 
```bash

CERTIFICATE_PERIOD=365
POD_IDENTITY_SERVICE_NAME=pod-identity-webhook
POD_IDENTITY_SECRET_NAME=pod-identity-webhook
POD_IDENTITY_SERVICE_NAMESPACE=kube-system

CERT_DiR=$PWD/webhook
mkdir -p $CERT_DiR

openssl req \
-x509 \
-newkey rsa:2048 \
-days $CERTIFICATE_PERIOD \
-nodes \
-keyout $CERT_DiR/tls.key \
-out $CERT_DiR/tls.crt \
-extensions 'v3_req' \
-config <( \
  echo '[req]'; \
  echo 'distinguished_name = req_distinguished_name'; \
  echo 'x509_extensions = v3_req'; \
  echo 'prompt = no'; \
  echo '[req_distinguished_name]'; \
  echo 'CN = pod-identity-webhook.kube-system.svc'; \
  echo '[v3_req]'; \
  echo 'keyUsage=keyEncipherment,dataEncipherment'; \
  echo 'extendedKeyUsage=serverAuth'; \
  echo 'subjectAltName=DNS:pod-identity-webhook.kube-system.svc')

kubectl delete secret -n $POD_IDENTITY_SERVICE_NAMESPACE $POD_IDENTITY_SECRET_NAME
kubectl create secret generic $POD_IDENTITY_SECRET_NAME \
  --from-file=$CERT_DiR/tls.crt \
  --from-file=$CERT_DiR/tls.key \
  --namespace=$POD_IDENTITY_SERVICE_NAMESPACE

CA_BUNDLE=$(cat $CERT_DiR/tls.crt | base64 | tr -d '\n')

echo "*******cert********"
echo "$CA_BUNDLE"
echo "\n"
sed -i -e "s/caBundle:.*/caBundle: ${CA_BUNDLE}/g" $PWD/webhook/mutatingwebhook.yaml
```

Let's apply the yaml now

```
kubectl apply -n kube-system -f webhook
```

We have our mutating web hook running!  Check out its logs.

 

## Test it out!

Let's create an ECR repository, that is only accessible from our new role k8s-ecr.  Let's put a policy on the new ECR repository.  Substitute the stars with your AWS account id.

```bash
aws ecr set-repository-policy --repository-name $1 --registry-id ****** --policy-text '{ "Version": "2008-10-17", "Statement": [ { "Sid": "give access to dev", "Effect": "Allow", "Principal": { "AWS": [ "arn:aws:iam::******:role/k8s-ecr" ] }, "Action": [ "ecr:*" ] } ] }'
```

Push nginx up there, for testing only.  Fill in your AWS account.  This will be our image we'll try to download with our testing app, with the projected JWT token...

```
docker pull nginx:latest
docker tag nginx:latest <AWS ACCOUNT ID>.dkr.ecr.us-east-1.amazonaws.com/test:0.0.1
aws ecr get-login-password | docker login --username AWS --password-stdin <AWS ACCOUNT ID>.dkr.ecr.us-east-1.amazonaws.com
docker push <AWS ACCOUNT ID>.dkr.ecr.us-east-1.amazonaws.com/test:0.0.1
```


Let's create a workload with this role!  We can just use nginx again, or anything really.  Fill in your Account ID.

```
kubectl create -n security sa test
kubectl annotate -n security sa test eks.amazonaws.com/role-arn=arn:aws:iam::<AWS ACCOUNT ID>:role/k8s-ecr
kubectl run test -n security --image nginx:latest --serviceaccount test
```

Let's exec into the pod, and see what the mutator did!


Now, let's go get that token, and hijack it!  We'll use that token to see if we can access our AWS ecr repo.  We'll take that token, set a the environment variables we'll need (AWS_WEB_IDENTITY_TOKEN_FILE, AWS_REGION, and AWS_ROLE_ARN), and run this java program (fill in the stars with your AWS account id):

Grab the token, execute

```
cd runner
kubectl exec -ti test -- cat /var/run/secrets/eks.amazonaws.com/serviceaccount/token > token.txt
```

Now we have what we need, no more need for the pod...  delete it 

```
kubectl delete pod -n security test
```

Now we are in the runner directory.  Let's build out app.  Run this script.  Fill in your AWS account:

```
mvn clean package
export AWS_REGION="us-east-1"
export AWS_ACCOUNT="<AWS ACCOUNT ID>"
export AWS_ROLE_ARN="arn:aws:iam::<AWS ACCOUNT ID>:role/k8s-ecr"
export AWS_WEB_IDENTITY_TOKEN_FILE="token.txt" 
java -jar target/tester-1.0.0.jar 
```



If we get a result, we'll have access to the ECR image!  For completeness, this was the pom.xml with dependencies I used to build this java program.

You should see:

*Success, our token works!!!!, image size in bytes: 53609086*

This means we downloaded the image!
