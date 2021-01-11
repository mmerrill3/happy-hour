import java.util.Base64;
import java.util.concurrent.TimeUnit;

import com.amazonaws.auth.WebIdentityTokenCredentialsProvider;
import com.amazonaws.services.ecr.AmazonECR;
import com.amazonaws.services.ecr.AmazonECRClientBuilder;
import com.amazonaws.services.ecr.model.AuthorizationData;
import com.amazonaws.services.ecr.model.DescribeImagesRequest;
import com.amazonaws.services.ecr.model.DescribeImagesResult;
import com.amazonaws.services.ecr.model.GetAuthorizationTokenRequest;
import com.amazonaws.services.ecr.model.GetAuthorizationTokenResult;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;

public class TestRunner {	
	
	//Set 4 environment variables 
	//1 AWS_REGION, I use us-east-1
	//2 AWS_WEB_IDENTITY_TOKEN_FILE, the path to where the token file is
	//3 AWS_ROLE_ARN, the ARN for your role.
	//4 AWS_ACCOUNT you're account id
	
	public static void main(String[] args) throws InterruptedException {
		System.out.println("token file is : " + System.getenv("AWS_WEB_IDENTITY_TOKEN_FILE"));	
		String account = System.getenv("AWS_ACCOUNT");	
		AmazonECR ecrClient = AmazonECRClientBuilder.defaultClient();
		
		GetAuthorizationTokenRequest tokenRequest = new GetAuthorizationTokenRequest();	
		tokenRequest.setRequestCredentialsProvider(WebIdentityTokenCredentialsProvider.create());
		GetAuthorizationTokenResult tokenResponse = ecrClient.getAuthorizationToken(tokenRequest);
		for(AuthorizationData authData : tokenResponse.getAuthorizationData()) {
			String userPassword = new String(Base64.getDecoder().decode(authData.getAuthorizationToken()));
		    String user = userPassword.substring(0, userPassword.indexOf(":"));
		    String password = userPassword.substring(userPassword.indexOf(":")+1);
		    
		    System.out.println("User is : " + user);
		    System.out.println("password is : " + password);
		    
		    DefaultDockerClientConfig config
			  = DefaultDockerClientConfig.createDefaultConfigBuilder()
			    .withRegistryPassword(password)
			    .withRegistryUsername(user)
			    .withDockerTlsVerify("0")
			    .withDockerHost("unix:///var/run/docker.sock").build();			
		    DockerClient dockerClient = DockerClientBuilder.getInstance(config).build();
		    
		    dockerClient.pullImageCmd(account + ".dkr.ecr.us-east-1.amazonaws.com/test")
		    .withTag("0.0.1")
		    .exec(new PullImageResultCallback())
		    .awaitCompletion(30, TimeUnit.SECONDS);			
		}		
		DescribeImagesRequest ecrRequest = new DescribeImagesRequest();
		ecrRequest.setRequestCredentialsProvider(WebIdentityTokenCredentialsProvider.create());
		ecrRequest.setRegistryId(account);
		ecrRequest.setRepositoryName("test");
		DescribeImagesResult result = ecrClient.describeImages(ecrRequest);	
		System.out.println("Success, our token works!!!!, image size in bytes: " + result.getImageDetails().get(0).getImageSizeInBytes());
	}
}
