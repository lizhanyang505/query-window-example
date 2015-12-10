/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dataartisans.querycommon.actors;

import akka.actor.ActorNotFound;
import akka.actor.ActorRef;
import akka.actor.ActorSelection;
import akka.actor.UntypedActor;
import akka.dispatch.Futures;
import akka.dispatch.Mapper;
import akka.dispatch.OnSuccess;
import akka.dispatch.Recover;
import akka.pattern.Patterns;
import akka.util.Timeout;
import com.dataartisans.querycommon.RetrievalService;
import com.dataartisans.querycommon.WrongKeyPartitionException;
import com.dataartisans.querycommon.messages.QueryState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.concurrent.ExecutionContext;
import scala.concurrent.ExecutionContext$;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ForkJoinPool;

public class QueryActor<K extends Serializable> extends UntypedActor {
	private static final Logger LOG = LoggerFactory.getLogger(QueryActor.class);

	private final RetrievalService<K> retrievalService;

	private final FiniteDuration askTimeout;
	private final FiniteDuration lookupTimeout;
	private final int queryAttempts;

	private final Map<K, ActorRef> cache = new HashMap<>();
	private final Object cacheLock = new Object();

	private final ExecutionContext executor;

	public QueryActor(
			RetrievalService<K> retrievalService,
			FiniteDuration lookupTimeout,
			FiniteDuration queryTimeout,
			int queryAttempts) throws Exception {
		this.retrievalService = retrievalService;
		this.askTimeout = queryTimeout;
		this.lookupTimeout = lookupTimeout;
		this.queryAttempts = queryAttempts;

		this.executor = ExecutionContext$.MODULE$.fromExecutor(new ForkJoinPool());

		retrievalService.start();
	}

	@Override
	public void postStop() throws Exception {
		if (retrievalService != null) {
			retrievalService.stop();
		}
	}

	@Override
	public void onReceive(Object message) throws Exception {
		if (message instanceof QueryState) {
			@SuppressWarnings("unchecked")
			QueryState<K> queryState = (QueryState<K>) message;

			LOG.debug("Query state for key {}.", queryState.getKey());

			Future<Object> futureResult = queryStateFutureWithFailover(queryAttempts, queryState);

			Patterns.pipe(futureResult, getContext().dispatcher()).to(getSender());
		}
	}

	public void refreshCache() throws Exception {
		LOG.debug("Refresh local and retrieval service cache.");
		synchronized (cacheLock) {
			cache.clear();
		}

		retrievalService.refreshActorCache();
	}

	public Future<ActorRef> getActorRefFuture(final K key) {
		synchronized (cacheLock) {
			ActorRef result = cache.get(key);

			if(result != null) {
				return Futures.successful(result);
			}
		}

		LOG.debug("Retrieve actor URL from retrieval service.");
		String actorURL = retrievalService.retrieveActorURL(key);

		if (actorURL == null) {
			return null;
		} else {
			ActorSelection selection = getContext().system().actorSelection(actorURL);

			LOG.debug("Resolve actor URL to ActorRef.");
			Future<ActorRef> actorRefFuture = selection.resolveOne(lookupTimeout);

			actorRefFuture.onSuccess(new OnSuccess<ActorRef>() {
				@Override
				public void onSuccess(ActorRef result) throws Throwable {
					synchronized (cacheLock) {
						cache.put(key, result);
					}
				}
			}, executor);

			return actorRefFuture;
		}
	}

	public Future<Object> queryStateFuture(final QueryState<K> queryState) {
		LOG.debug("Try to get ActorRef future for key {}.", queryState.getKey());

		Future<ActorRef> actorRefFuture = getActorRefFuture(queryState.getKey());

		@SuppressWarnings("unchecked")
		Future<Object> result =  actorRefFuture.flatMap(new Mapper<ActorRef, Future<Object>>() {
			public Future<Object> apply(ActorRef actorRef) {
				LOG.debug("Ask response actor for state for key {}.", queryState.getKey());
				return Patterns.ask(actorRef, queryState, new Timeout(askTimeout));
			}
		}, executor).recoverWith(new Recover<Future<Object>>() {
			@Override
			public Future<Object> recover(final Throwable failure) throws Throwable {
				if (failure instanceof WrongKeyPartitionException || failure instanceof ActorNotFound) {
					// wait askTimeout because we communicated with the wrong actor. This usually
					// indicates that not all actors have registered at the registry.
					return Patterns.after(
						askTimeout,
						getContext().system().scheduler(),
						executor,
						new Callable<Future<Object>>() {
							@Override
							public Future<Object> call() throws Exception {
								refreshCache();
								return Futures.failed(failure);
							}
						});
				} else {
					LOG.debug("State query failed with {}.", failure);
					refreshCache();
					return Futures.failed(failure);
				}
			}
		}, executor);

		return result;
	}

	public Future<Object> queryStateFutureWithFailover(final int tries, final QueryState<K> queryState) {
		@SuppressWarnings("unchecked")
		Future<Object> result = queryStateFuture(queryState).recoverWith(new Recover<Future<Object>>() {
			@Override
			public Future<Object> recover(Throwable failure) throws Throwable {
				if (tries > 0) {
					LOG.debug("Query state failed with {}. Try to recover. #{} left.", failure, tries - 1);
					return queryStateFutureWithFailover(tries - 1, queryState);
				} else {
					return Futures.failed(failure);
				}
			}
		}, executor);

		return result;
	}
}
