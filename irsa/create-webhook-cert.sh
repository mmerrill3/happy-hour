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
