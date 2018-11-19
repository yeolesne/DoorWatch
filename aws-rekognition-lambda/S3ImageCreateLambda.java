package lambda;

import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.rekognition.AmazonRekognition;
import com.amazonaws.services.rekognition.AmazonRekognitionClientBuilder;
import com.amazonaws.services.rekognition.model.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.CopyObjectRequest;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import model.LambdaParameters;

import java.text.SimpleDateFormat;
import java.util.*;

public class S3ImageCreateLambda implements RequestHandler<S3Event, LambdaParameters> {

  String region = "us-east-1";

  public LambdaParameters handleRequest(S3Event s3Event, Context context) {

    context.getLogger()
        .log("Input Function [" + context.getFunctionName() + "], S3Event [" + s3Event.toJson().toString() + "]");
    LambdaParameters parameters = LambdaParameters
        .builder()
        .bucketName(s3Event.getRecords().get(0).getS3().getBucket().getName())
        .s3Key(s3Event.getRecords().get(0).getS3().getObject().getKey())
        .stepFunctionId(UUID.randomUUID()).build();
    context.getLogger().log("S3 upload parameter values " + parameters.toString());
    System.out.println("S3 Upload parameters : " + parameters.toString());

    Set<String> actualLabels = detectHuman(parameters);
    if (isHumanDetected(actualLabels)) {
      context.getLogger().log("Human found in image");
      copyImageToHuman(parameters);
      deleteFromOrigin(parameters);
    } else {
      context.getLogger().log("Human not present in image");
    }
    return parameters;
  }

  private void deleteFromOrigin(LambdaParameters parameters) {
    AmazonS3 s3Client = AmazonS3ClientBuilder
        .standard()
        .withCredentials(new EnvironmentVariableCredentialsProvider())
        .withRegion(region)
        .build();
    DeleteObjectRequest deleteObjectRequest = new DeleteObjectRequest(
        parameters.getBucketName(),
        parameters.getS3Key());
    s3Client.deleteObject(deleteObjectRequest);
  }

  private void copyImageToHuman(LambdaParameters parameters) {
    String newKey = getTodayDate() + "/" + parameters.getS3Key();
    AmazonS3 s3Client = AmazonS3ClientBuilder
        .standard()
        .withCredentials(new EnvironmentVariableCredentialsProvider())
        .withRegion(region)
        .build();
    System.out.println("New key " + newKey);
    CopyObjectRequest copyObjectRequest = new CopyObjectRequest(
        parameters.getBucketName(),
        parameters.getS3Key(),
        parameters.getBucketName(),
        newKey);
    s3Client.copyObject(copyObjectRequest);
  }

  private boolean isHumanDetected(Set<String> actualLabels) {
    List<String> expectedLables = Arrays.asList("Human", "Person", "Child",
        "People", "Kid", "Girl", "Female");
    return actualLabels.stream().anyMatch(new HashSet<>(expectedLables)::contains);
  }

  private Set<String> detectHuman(LambdaParameters parameters) {
    AmazonRekognition amazonRekognitionClient = AmazonRekognitionClientBuilder
        .defaultClient();
    DetectLabelsRequest request = new DetectLabelsRequest()
        .withImage(
            new Image().withS3Object(
                new S3Object().withName(parameters.getS3Key()).withBucket(parameters.getBucketName())
            )
        )
        .withMaxLabels(10)
        .withMinConfidence(75F);
    DetectLabelsResult result = amazonRekognitionClient.detectLabels(request);
    List<Label> labels = result.getLabels();
    Set<String> actualLabels = new HashSet<>();
    for (Label label : labels) {
      actualLabels.add(label.getName());
    }
    return actualLabels;
  }

  public String getTodayDate() {
    SimpleDateFormat formatter = new SimpleDateFormat("dd-MM-yyyy");
    Date date = new Date();
    return formatter.format(date);
  }
}