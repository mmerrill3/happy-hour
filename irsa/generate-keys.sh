# Generate the keypair
PRIV_KEY="oidc/sa-signer.key"
PUB_KEY="oidc/sa-signer.key.pub"
PKCS_KEY="oidc/sa-signer-pkcs8.pub"
# Generate a key pair
ssh-keygen -t rsa -b 2048 -f $PRIV_KEY -m pem
# convert the SSH pubkey to PKCS8
ssh-keygen -e -m PKCS8 -f $PUB_KEY > $PKCS_KEY
