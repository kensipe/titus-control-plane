/*
 * Copyright 2019 Netflix, Inc.
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

package com.netflix.titus.runtime.endpoint.common.grpc;

import java.util.stream.Collectors;

import com.netflix.titus.api.model.Page;
import com.netflix.titus.api.model.callmetadata.CallMetadata;
import com.netflix.titus.api.model.callmetadata.Caller;
import com.netflix.titus.api.model.callmetadata.CallerType;
import com.netflix.titus.grpc.protogen.Pagination;

public class CommonRuntimeGrpcModelConverters {

    public static Page toPage(com.netflix.titus.grpc.protogen.Page grpcPage) {
        return new Page(grpcPage.getPageNumber(), grpcPage.getPageSize(), grpcPage.getCursor());
    }

    public static com.netflix.titus.grpc.protogen.Page toGrpcPage(Page runtimePage) {
        return com.netflix.titus.grpc.protogen.Page.newBuilder()
                .setPageNumber(runtimePage.getPageNumber())
                .setPageSize(runtimePage.getPageSize())
                .setCursor(runtimePage.getCursor())
                .build();
    }

    public static com.netflix.titus.api.model.Pagination toPagination(Pagination grpcPagination) {
        return new com.netflix.titus.api.model.Pagination(
                toPage(grpcPagination.getCurrentPage()),
                grpcPagination.getHasMore(),
                grpcPagination.getTotalPages(),
                grpcPagination.getTotalItems(),
                grpcPagination.getCursor(),
                grpcPagination.getCursorPosition()
        );
    }

    public static Pagination toGrpcPagination(com.netflix.titus.api.model.Pagination runtimePagination) {
        return Pagination.newBuilder()
                .setCurrentPage(toGrpcPage(runtimePagination.getCurrentPage()))
                .setTotalItems(runtimePagination.getTotalItems())
                .setTotalPages(runtimePagination.getTotalPages())
                .setHasMore(runtimePagination.hasMore())
                .setCursor(runtimePagination.getCursor())
                .setCursorPosition(runtimePagination.getCursorPosition())
                .build();
    }

    public static Pagination emptyGrpcPagination(com.netflix.titus.grpc.protogen.Page page) {
        return Pagination.newBuilder()
                .setCurrentPage(page)
                .setTotalItems(0)
                .setTotalPages(0)
                .setHasMore(false)
                .setCursor("")
                .setCursorPosition(0)
                .build();
    }

    public static CallMetadata toCallMetadata(com.netflix.titus.grpc.protogen.CallMetadata grpcCallContext) {
        return CallMetadata.newBuilder()
                .withCallerId(grpcCallContext.getCallerId())
                .withCallReason(grpcCallContext.getCallReason())
                .withCallPath(grpcCallContext.getCallPathList())
                .withCallers(grpcCallContext.getCallersList().stream().map(CommonRuntimeGrpcModelConverters::toCoreCaller).collect(Collectors.toList()))
                .withDebug(grpcCallContext.getDebug())
                .build();
    }

    public static CallerType toCoreCallerType(com.netflix.titus.grpc.protogen.CallMetadata.CallerType grpcCallerType) {
        switch (grpcCallerType) {
            case Application:
                return CallerType.Application;
            case User:
                return CallerType.User;
            case Unknown:
            case UNRECOGNIZED:
            default:
                return CallerType.Unknown;
        }
    }

    private static Caller toCoreCaller(com.netflix.titus.grpc.protogen.CallMetadata.Caller grpcCaller) {
        return Caller.newBuilder()
                .withId(grpcCaller.getId())
                .withCallerType(toCoreCallerType(grpcCaller.getType()))
                .withContext(grpcCaller.getContextMap())
                .build();
    }

    public static com.netflix.titus.grpc.protogen.CallMetadata toGrpcCallMetadata(CallMetadata callMetadata) {
        return com.netflix.titus.grpc.protogen.CallMetadata.newBuilder()
                .setCallerId(callMetadata.getCallerId())
                .addAllCallers(callMetadata.getCallers().stream().map(CommonRuntimeGrpcModelConverters::toGrpcCaller).collect(Collectors.toList()))
                .setCallReason(callMetadata.getCallReason())
                .addAllCallPath(callMetadata.getCallPath())
                .setDebug(callMetadata.isDebug())
                .build();
    }

    public static com.netflix.titus.grpc.protogen.CallMetadata.CallerType toGrpcCallerType(CallerType callerType) {
        if (callerType == null) {
            return com.netflix.titus.grpc.protogen.CallMetadata.CallerType.Unknown;
        }
        switch (callerType) {
            case Application:
                return com.netflix.titus.grpc.protogen.CallMetadata.CallerType.Application;
            case User:
                return com.netflix.titus.grpc.protogen.CallMetadata.CallerType.User;
            case Unknown:
            default:
                return com.netflix.titus.grpc.protogen.CallMetadata.CallerType.Unknown;
        }
    }

    public static com.netflix.titus.grpc.protogen.CallMetadata.Caller toGrpcCaller(Caller coreCaller) {
        return com.netflix.titus.grpc.protogen.CallMetadata.Caller.newBuilder()
                .setId(coreCaller.getId())
                .setType(toGrpcCallerType(coreCaller.getCallerType()))
                .putAllContext(coreCaller.getContext())
                .build();
    }
}

