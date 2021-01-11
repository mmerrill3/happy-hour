CA_THUMBPRINT=$(openssl s_client -connect s3.amazonaws.com:443 -servername s3.amazonaws.com \
  -showcerts < /dev/null 2>/dev/null | openssl x509 -in /dev/stdin -sha1 -noout -fingerprint | cut -d '=' -f 2 | tr -d ':')

aws iam create-open-id-connect-provider \
     --url https://s3.amazonaws.com/auth.k8s.mmerrilldev.net \
     --thumbprint-list $CA_THUMBPRINT \
     --client-id-list sts.amazonaws.com
