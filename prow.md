# Prow Github site
https://github.com/kubernetes/test-infra/tree/master/prow

## Prow install md site
https://github.com/kubernetes/test-infra/blob/master/prow/getting_started_deploy.md

## Prow install steps
* Setup k8s cluster (I used kops verison 1.14.beta2)
* Setup the bot github account
    * I made mmerrill3-bot
    * Push access given to https://github.com/mmerrill3/wls-ingress
    * Generate personal access token and saved to my home directory
    * Created cluster admin role called cluster-admin-mmerrill
        * `kubectl create clusterrolebinding cluster-admin-binding-"${USER}" --clusterrole=cluster-admin --user="${USER}"`
    * Created hmac secret
        * `openssl rand -hex 20 > ~/hmac-secret`
        * `kubectl create secret generic hmac-token --from-file=hmac=/Users/mmerrill/hmac-secret`
    * Create token for oauth2 secret from github
        * `kubectl create secret generic oauth-token --from-file=oauth=/Users/mmerrill/bot-token`
* Apply the prow deployment
    * `kubectl apply -f https://raw.githubusercontent.com/kubernetes/test-infra/master/prow/cluster/starter.yaml`
    * This does not account for an ingress controller, you need one
    * I installed helm and an nginx ingress controller
    * My load balancer shows prow is up: https://a59b13db3dbb711e9b257061fcacb32d-642794243.us-east-1.elb.amazonaws.com/
* Add the plugins
    * Checkout the github project under go source root
        * `git clone https://github.com/kubernetes/test-infra.git`
        * edit a plugin.yaml file
plugins:
mmerrill3/wls-ingress:
- size
- assign
- lgtm 

    * Run the following
    * `kubectl create configmap plugins   --from-file=plugins.yaml=/Users/mmerrill/plugins.yaml --dry-run -o yaml   | kubectl replace configmap plugins -f -`
    * Create some PRs and fool around
    * Additional plugins are here: https://prow.k8s.io/plugins
