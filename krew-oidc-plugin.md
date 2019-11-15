---
title: 'Project documentation template'
disqus: hackmd
---

Using Krew
===



## Installing

I installed on my mac, here was the output:
mmerrillmbp:Downloads mmerrill$ (
```
>   set -x; cd "$(mktemp -d)" &&
>   curl -fsSLO "https://github.com/kubernetes-sigs/krew/releases/download/v0.3.2/krew.{tar.gz,yaml}" &&
>   tar zxvf krew.tar.gz &&
>   ./krew-"$(uname | tr '[:upper:]' '[:lower:]')_amd64" install \
>     --manifest=krew.yaml --archive=krew.tar.gz
> )
++ mktemp -d
+ cd /var/folders/_0/j_jhnbmd0wgg1wd9_9kz5ss4sm016l/T/tmp.J69C5E6l
+ curl -fsSLO 'https://github.com/kubernetes-sigs/krew/releases/download/v0.3.2/krew.{tar.gz,yaml}'
+ tar zxvf krew.tar.gz
x ./krew-darwin_amd64
x ./krew-linux_amd64
x ./krew-linux_arm
x ./krew-windows_amd64.exe
++ uname
++ tr '[:upper:]' '[:lower:]'
+ ./krew-darwin_amd64 install --manifest=krew.yaml --archive=krew.tar.gz
Installing plugin: krew
CAVEATS:
\
 |  krew is now installed! To start using kubectl plugins, you need to add
 |  krew's installation directory to your PATH:
 |  
 |    * macOS/Linux:
 |      - Add the following to your ~/.bashrc or ~/.zshrc:
 |          export PATH="${KREW_ROOT:-$HOME/.krew}/bin:$PATH"
 |      - Restart your shell.
 |  
 |    * Windows: Add %USERPROFILE%\.krew\bin to your PATH environment variable
 |  
 |  To list krew commands and to get help, run:
 |    $ kubectl krew
 |  For a full list of available plugins, run:
 |    $ kubectl krew search
 |  
 |  You can find documentation at https://sigs.k8s.io/krew.
/
Installed plugin: krew
WARNING: You installed a plugin from the krew-index plugin repository.
   These plugins are not audited for security by the Krew maintainers.
   Run them at your own risk.
```

I checked the install, looked ok except for the .net plugin?

```
mmerrillmbp:Downloads mmerrill$ kubectl plugin list
The following compatible plugins are available:

/Users/mmerrill/.krew/bin/kubectl-krew
Unable read directory "~/.dotnet/tools" from your PATH: open ~/.dotnet/tools: no such file or directory. Skipping...
```

I installed my cluster in AWS using kops 1.14.1:

`mmerrillmbp:clair mmerrill$ kops create cluster --name k8s.mmerrilldev.com --topology private --node-count 3 --master-count 3 --networking weave --zones us-east-1c,us-east-1d,us-east-1e --bastion --cloud aws --ssh-public-key ~/.ssh/kube.pub --yes`

To setup oidc for my cluster, I updated the APIServer to have the following info:

```
  kubeAPIServer:
    oidcClientID: <myclient>.apps.googleusercontent.com
    oidcIssuerURL: https://accounts.google.com
    oidcUsernameClaim: email
```

Next, I needed to initialize the krew index

```
mmerrillmbp:Downloads mmerrill$ kubectl krew update
Updated the local copy of plugin index.
```
The plugins available to krew are here:
https://github.com/kubernetes-sigs/krew-index/blob/master/plugins.md

You can see this list by running krew search

```
mmerrillmbp:Downloads mmerrill$ kubectl krew search
NAME                            DESCRIPTION                                         INSTALLED
access-matrix                   Show an RBAC access matrix for server resources     no
auth-proxy                      Authentication proxy to a pod or service            no
bulk-action                     Do bulk actions on Kubernetes resources.            no
ca-cert                         Print the PEM CA certificate of the current clu...  no
change-ns                       View or change the current namespace via kubectl.   no
config-cleanup                  Automatically clean up your kubeconfig              no
cssh                            SSH into Kubernetes nodes                           no
ctx                             Switch between contexts in your kubeconfig          no
custom-cols                     A "kubectl get" replacement with customizable c...  no
debug                           Attach ephemeral debug container to running pod     no
debug-shell                     Create pod with interactive kube-shell.             no
doctor                          Scans your cluster and reports anomalies.           no
eksporter                       Export resources and removes a pre-defined set ...  no
evict-pod                       Evicts the given pod                                no
exec-as                         Like kubectl exec, but offers a `user` flag to ...  no
exec-cronjob                    Run a CronJob immediately as Job                    no
fields                          Grep resources hierarchy by field name              no
get-all                         Like 'kubectl get all', but _really_ everything     no
gke-credentials                 Fetch credentials for GKE clusters                  no
gopass                          Imports secrets from gopass                         no
grep                            Filter Kubernetes resources by matching their n...  no
iexec                           Interactive selection tool for `kubectl exec`       no
ingress-nginx                   Interact with ingress-nginx                         no
konfig                          Merge, split or import kubeconfig files             no
krew                            Package manager for kubectl plugins.                yes
kubesec-scan                    Scan Kubernetes resources with kubesec.io.          no
kudo                            Declaratively build, install, and run operators...  no
match-name                      Match names of pods and other API objects           no
modify-secret                   modify secret with implicit base64 translations     no
mtail                           Tail logs from multiple pods matching label sel...  no
neat                            Remove clutter from Kubernetes manifests to mak...  no
node-admin                      List nodes and run privileged pod with chroot       no
ns                              Switch between Kubernetes namespaces                no
oidc-login                      Log in to the OpenID Connect provider               no
open-svc                        Open the Kubernetes URL(s) for the specified se...  no
outdated                        Finds outdated container images running in a cl...  no
passman                         Store kubeconfig credentials in keychains or pa...  no
pod-logs                        Display a list of pods to get logs from             no
pod-shell                       Display a list of pods to execute a shell in        no
preflight                       Executes application preflight tests in a cluster   no
prompt                          Prompts for user confirmation when executing co...  no
prune-unused                    Prune unused resources                              no
rbac-lookup                     Reverse lookup for RBAC                             no
rbac-view                       A tool to visualize your RBAC permissions.          no
resource-capacity               Provides an overview of resource requests, limi...  no
restart                         Restarts a pod with the given name                  no
rm-standalone-pods              Remove all pods without owner references            no
sniff                           Start a remote packet capture on pods using tcp...  no
sort-manifests                  Sort manifest files in a proper order by Kind       no
ssh-jump                        A kubectl plugin to SSH into Kubernetes nodes u...  no
sudo                            Run Kubernetes commands impersonated as group s...  no
support-bundle                  Creates support bundles for off-cluster analysis    no
tail                            Stream logs from multiple pods and containers u...  no
view-secret                     Decode Kubernetes secrets
                                no
view-serviceaccount-kubeconfig  Show a kubeconfig setting to access the apiserv...  no
view-utilization                Shows cluster cpu and memory utilization            no
virt                            Control KubeVirt virtual machines using virtctl     no
warp                            Sync and execute local files in Pod                 no
who-can                         Shows who has RBAC permissions to access Kubern...  no
whoami                          Show the subject that's currently authenticated...  no
```

Install the krew oidc-plugin

```
kubectl krew install oidc-lmmerrillmbp:Downloads mmerrill$ kubectl krew install oidc-login
Updated the local copy of plugin index.
Installing plugin: oidc-login
CAVEATS:
\
 |  You need to setup the OIDC provider, Kubernetes API server, role binding and kubeconfig.
 |  See https://github.com/int128/kubelogin for more.
/
Installed plugin: oidc-login
WARNING: You installed a plugin from the krew-index plugin repository.
   These plugins are not audited for security by the Krew maintainers.
   Run them at your own risk.ogin
```

So, I need to configure this plugin to use my oidc provider with the client and secret I'll need.  But first, let's setup our oidc user (in this case gmail account) with some permissions.  Note that I wouldn't normally give a user cluster admin, but since this is a demo...

```
mmerrillmbp:.kube mmerrill$ kubectl get clusterrolebinding cluster-admin -o yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  annotations:
    rbac.authorization.kubernetes.io/autoupdate: "true"
  creationTimestamp: "2019-11-15T16:15:11Z"
  labels:
    kubernetes.io/bootstrapping: rbac-defaults
  name: cluster-admin
  resourceVersion: "3326"
  selfLink: /apis/rbac.authorization.k8s.io/v1/clusterrolebindings/cluster-admin
  uid: 199e7ea7-07c3-11ea-879a-0ae9e2d42aed
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: cluster-admin
subjects:
- apiGroup: rbac.authorization.k8s.io
  kind: Group
  name: system:masters
- apiGroup: rbac.authorization.k8s.io
  kind: User
  name: jjpaacks@gmail.com
```


Ok, now we can update our ~/.kubeconfig to add the hook to call the plugin when the user is used.  So, we go to the current context, and change the user.

```
mmerrillmbp:.kube mmerrill$ kubectl config set-context $(kubectl config current-context) --user=jjpaacks@gmail.com 
Context "k8s.mmerrilldev.com" modified.
```


Ok, now we add the user section in the users part of the kubeconfig.

```
- name: jjpaacks@gmail.com
  user:
    exec:
      apiVersion: client.authentication.k8s.io/v1beta1
      command: kubectl
      args:
      - oidc-login
      - get-token
      - --oidc-issuer-url=https://accounts.google.com
      - --oidc-client-id=<CLIENTID>.apps.googleusercontent.com
      - --oidc-client-secret=<SECRET>
      - --oidc-extra-scope=email
```

I had to add email as an extra claim, since that's what the APIServer is looking for in the token.

Now, the oidc logic is inline with all of our kubectl commands.  If we need a token, we'll go out and get one!

But, remember to allow your oidc client to redirect to localhost:8000, that's what the plugin registers as a callback URL.


Now, when I run kubectl commands, the flow will kick in.

The token gets stored here:

`~/.kube/cache/oidc-login`

If you need to get a new token (you need to change the claims, etc.), just delete the token in that directory.




