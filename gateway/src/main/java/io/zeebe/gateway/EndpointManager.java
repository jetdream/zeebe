/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.gateway;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.zeebe.gateway.ResponseMapper.BrokerResponseMapper;
import io.zeebe.gateway.cmd.BrokerErrorException;
import io.zeebe.gateway.cmd.ClientCommandRejectedException;
import io.zeebe.gateway.cmd.ClientException;
import io.zeebe.gateway.impl.broker.BrokerClient;
import io.zeebe.gateway.impl.broker.request.BrokerRequest;
import io.zeebe.gateway.impl.broker.response.BrokerResponse;
import io.zeebe.gateway.protocol.GatewayGrpc;
import io.zeebe.gateway.protocol.GatewayOuterClass.CancelWorkflowInstanceRequest;
import io.zeebe.gateway.protocol.GatewayOuterClass.CancelWorkflowInstanceResponse;
import io.zeebe.gateway.protocol.GatewayOuterClass.CreateJobRequest;
import io.zeebe.gateway.protocol.GatewayOuterClass.CreateJobResponse;
import io.zeebe.gateway.protocol.GatewayOuterClass.CreateWorkflowInstanceRequest;
import io.zeebe.gateway.protocol.GatewayOuterClass.CreateWorkflowInstanceResponse;
import io.zeebe.gateway.protocol.GatewayOuterClass.DeployWorkflowRequest;
import io.zeebe.gateway.protocol.GatewayOuterClass.DeployWorkflowResponse;
import io.zeebe.gateway.protocol.GatewayOuterClass.HealthRequest;
import io.zeebe.gateway.protocol.GatewayOuterClass.HealthResponse;
import io.zeebe.gateway.protocol.GatewayOuterClass.PublishMessageRequest;
import io.zeebe.gateway.protocol.GatewayOuterClass.UpdateWorkflowInstancePayloadRequest;
import io.zeebe.gateway.protocol.GatewayOuterClass.UpdateWorkflowInstancePayloadResponse;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

public class EndpointManager extends GatewayGrpc.GatewayImplBase {

  private final BrokerClient brokerClient;

  public EndpointManager(final BrokerClient brokerClient) {
    this.brokerClient = brokerClient;
  }

  @Override
  public void health(
      final HealthRequest request, final StreamObserver<HealthResponse> responseObserver) {
    sendRequest(
        request,
        RequestMapper::toTopologyRequest,
        ResponseMapper::toHealthResponse,
        responseObserver);
  }

  @Override
  public void deployWorkflow(
      final DeployWorkflowRequest request,
      final StreamObserver<DeployWorkflowResponse> responseObserver) {

    sendRequest(
        request,
        RequestMapper::toDeployWorkflowRequest,
        ResponseMapper::toDeployWorkflowResponse,
        responseObserver);
  }

  @Override
  public void publishMessage(
      PublishMessageRequest request, StreamObserver<Empty> responseObserver) {

    sendRequest(
        request,
        RequestMapper::toPublishMessageRequest,
        ResponseMapper::emptyResponse,
        responseObserver);
  }

  @Override
  public void createJob(
      CreateJobRequest request, StreamObserver<CreateJobResponse> responseObserver) {
    sendRequest(
        request,
        RequestMapper::toCreateJobRequest,
        ResponseMapper::toCreateJobResponse,
        responseObserver);
  }

  @Override
  public void createWorkflowInstance(
      CreateWorkflowInstanceRequest request,
      StreamObserver<CreateWorkflowInstanceResponse> responseObserver) {
    sendRequest(
        request,
        RequestMapper::toCreateWorkflowInstanceRequest,
        ResponseMapper::toCreateWorkflowInstanceResponse,
        responseObserver);
  }

  @Override
  public void cancelWorkflowInstance(
      CancelWorkflowInstanceRequest request,
      StreamObserver<CancelWorkflowInstanceResponse> responseObserver) {
    sendRequest(
        request,
        RequestMapper::toCancelWorkflowInstanceRequest,
        ResponseMapper::toCancelWorkflowInstanceResponse,
        responseObserver);
  }

  @Override
  public void updateWorkflowInstancePayload(
      UpdateWorkflowInstancePayloadRequest request,
      StreamObserver<UpdateWorkflowInstancePayloadResponse> responseObserver) {
    sendRequest(
        request,
        RequestMapper::toUpdateWorkflowInstancePayloadRequest,
        ResponseMapper::toUpdateWorkflowInstancePayloadResponse,
        responseObserver);
  }

  private <GrpcRequestT, BrokerResponseT, GrpcResponseT> void sendRequest(
      final GrpcRequestT grpcRequest,
      final Function<GrpcRequestT, BrokerRequest<BrokerResponseT>> requestMapper,
      final BrokerResponseMapper<BrokerResponseT, GrpcResponseT> responseMapper,
      final StreamObserver<GrpcResponseT> streamObserver) {

    final BrokerRequest<BrokerResponseT> brokerRequest;
    try {
      brokerRequest = requestMapper.apply(grpcRequest);
    } catch (Exception e) {
      streamObserver.onError(convertThrowable(e));
      return;
    }

    brokerClient.sendRequest(
        brokerRequest,
        (response, error) -> {
          try {
            if (error == null) {
              handleResponse(responseMapper, streamObserver, response);
            } else {
              streamObserver.onError(convertThrowable(error));
            }
          } catch (Exception e) {
            streamObserver.onError(
                convertThrowable(new ClientException("Unknown exception: " + e.getMessage())));
          }
        });
  }

  private <BrokerResponseT, GrpcResponseT> void handleResponse(
      BrokerResponseMapper<BrokerResponseT, GrpcResponseT> responseMapper,
      StreamObserver<GrpcResponseT> streamObserver,
      BrokerResponse<BrokerResponseT> response) {
    if (response.isResponse()) {
      final GrpcResponseT grpcResponse =
          responseMapper.apply(
              response.getPartitionId(), response.getKey(), response.getResponse());
      streamObserver.onNext(grpcResponse);
      streamObserver.onCompleted();
    } else if (response.isRejection()) {
      final Throwable exception = new ClientCommandRejectedException(response.getRejection());
      streamObserver.onError(convertThrowable(exception));
    } else if (response.isError()) {
      final Throwable exception = new BrokerErrorException(response.getError());
      streamObserver.onError(convertThrowable(exception));
    } else {
      streamObserver.onError(
          convertThrowable(new ClientException("Unknown exception for response: " + response)));
    }
  }

  private static StatusRuntimeException convertThrowable(final Throwable cause) {
    final String description;

    if (cause instanceof ExecutionException) {
      description = cause.getCause().getMessage();
    } else {
      description = cause.getMessage();
    }

    return Status.INTERNAL.augmentDescription(description).asRuntimeException();
  }
}
