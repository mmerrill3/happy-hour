mvn clean package
export AWS_REGION="us-east-1"
export AWS_ACCOUNT="565009088587"
export AWS_ROLE_ARN="arn:aws:iam::565009088587:role/k8s-ecr"
export AWS_WEB_IDENTITY_TOKEN_FILE="token.txt" 
java -jar target/tester-1.0.0.jar 

