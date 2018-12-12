// Copyright (c) 2018, Yubico AB
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// 1. Redistributions of source code must retain the above copyright notice, this
//    list of conditions and the following disclaimer.
//
// 2. Redistributions in binary form must reproduce the above copyright notice,
//    this list of conditions and the following disclaimer in the documentation
//    and/or other materials provided with the distribution.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
// FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
// DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
// SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
// CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
// OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package com.yubico.webauthn;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yubico.webauthn.data.ByteArray;
import java.util.Collections;
import java.util.List;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;


@Value
@Builder
public class AssertionResult {

    private final boolean success;

    @NonNull
    private final ByteArray credentialId;

    @NonNull
    private final ByteArray userHandle;

    @NonNull
    private final String username;

    private final long signatureCount;

    private final boolean signatureCounterValid;

    @NonNull
    private final List<String> warnings;

    @JsonCreator
    private AssertionResult(
        @JsonProperty("success") boolean success,
        @NonNull @JsonProperty("credentialId") ByteArray credentialId,
        @NonNull @JsonProperty("userHandle") ByteArray userHandle,
        @NonNull @JsonProperty("username") String username,
        @JsonProperty("signatureCount") long signatureCount,
        @JsonProperty("signatureCounterValid") boolean signatureCounterValid,
        @NonNull @JsonProperty("warnings") List<String> warnings
    ) {
        this.success = success;
        this.credentialId = credentialId;
        this.userHandle = userHandle;
        this.username = username;
        this.signatureCount = signatureCount;
        this.signatureCounterValid = signatureCounterValid;
        this.warnings = Collections.unmodifiableList(warnings);
    }

    static AssertionResultBuilder.MandatoryStages builder() {
        return new AssertionResultBuilder.MandatoryStages();
    }

    static class AssertionResultBuilder {
        public static class MandatoryStages {
            private final AssertionResultBuilder builder = new AssertionResultBuilder();

            public Step2 success(boolean success) {
                builder.success(success);
                return new Step2();
            }

            public class Step2 {
                public Step3 credentialId(ByteArray credentialId) {
                    builder.credentialId(credentialId);
                    return new Step3();
                }
            }

            public class Step3 {
                public Step4 userHandle(ByteArray userHandle) {
                    builder.userHandle(userHandle);
                    return new Step4();
                }
            }

            public class Step4 {
                public Step5 username(String username) {
                    builder.username(username);
                    return new Step5();
                }
            }

            public class Step5 {
                public Step6 signatureCount(long signatureCount) {
                    builder.signatureCount(signatureCount);
                    return new Step6();
                }
            }

            public class Step6 {
                public Step7 signatureCounterValid(boolean signatureCounterValid) {
                    builder.signatureCounterValid(signatureCounterValid);
                    return new Step7();
                }
            }

            public class Step7 {
                public AssertionResultBuilder warnings(List<String> warnings) {
                    return builder.warnings(warnings);
                }
            }
        }
    }

}

