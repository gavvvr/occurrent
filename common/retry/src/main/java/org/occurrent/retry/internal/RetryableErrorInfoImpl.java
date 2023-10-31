/*
 *
 *  Copyright 2023 Johan Haleby
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.occurrent.retry.internal;

import org.jetbrains.annotations.Nullable;
import org.occurrent.retry.RetryInfo;
import org.occurrent.retry.RetryableErrorInfo;

import java.time.Duration;

record RetryableErrorInfoImpl(RetryInfo retryInfo, @Nullable Duration backoffDurationBeforeNextRetry) implements RetryableErrorInfo {
    @Override
    public int getRetryCount() {
        return retryInfo.getRetryCount();
    }

    @Override
    public int getAttemptNumber() {
        return retryInfo.getAttemptNumber();
    }

    @Override
    public int getMaxAttempts() {
        return retryInfo.getMaxAttempts();
    }

    @Override
    public int getAttemptsLeft() {
        return retryInfo.getAttemptsLeft();
    }

    @Override
    public boolean isInfiniteRetriesLeft() {
        return retryInfo.isInfiniteRetriesLeft();
    }

    @Override
    public Duration getBackoff() {
        return retryInfo.getBackoff();
    }

    @Override
    public boolean isLastAttempt() {
        return retryInfo.isLastAttempt();
    }

    @Override
    public boolean isFirstAttempt() {
        return retryInfo.isFirstAttempt();
    }

    @Override
    public Duration getBackoffBeforeNextRetryAttempt() {
        return backoffDurationBeforeNextRetry;
    }
}