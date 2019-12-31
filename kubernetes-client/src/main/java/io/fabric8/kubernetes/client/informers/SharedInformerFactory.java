/**
 * Copyright (C) 2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.kubernetes.client.informers;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.base.BaseOperation;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.dsl.base.OperationContext;
import io.fabric8.kubernetes.client.informers.impl.DefaultSharedIndexInformer;
import okhttp3.OkHttpClient;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static io.fabric8.kubernetes.client.utils.Utils.getPluralFromKind;

/**
 * SharedInformerFactory class constructs and caches informers for api types.
 *
 * This has been taken from https://github.com/kubernetes-client/java/blob/master/util/src/main/java/io/kubernetes/client/informer/SharedInformerFactory.java
 * which is ported from offical go client https://github.com/kubernetes/client-go/blob/master/informers/factory.go
 */
public class SharedInformerFactory extends BaseOperation {
  private Map<Type, SharedIndexInformer> informers;

  private Map<Type, Future> startedInformers;

  private ExecutorService informerExecutor;

  private BaseOperation baseOperation;

  /**
   * Constructor with thread pool specified.
   *
   * @param threadPool specified thread pool.
   * @param okHttpClient OkHttp client
   * @param configuration configuration for client
   */
  public SharedInformerFactory(ExecutorService threadPool, OkHttpClient okHttpClient, Config configuration) {
    super(new OperationContext().withOkhttpClient(okHttpClient).withConfig(configuration));
    this.informerExecutor = threadPool;
    this.informers = new HashMap<>();
    this.startedInformers = new HashMap<>();
    this.baseOperation = this.newInstance(context);
  }

  /**
   * Constructs and returns a shared index informer with resync period specified. And the
   * informer cache will be overwritten.
   *
   * @param apiTypeClass apiType class
   * @param apiListTypeClass api list type class
   * @param resyncPeriodInMillis resync period in milliseconds
   * @param <T> the type parameter
   * @param <TList> the type's list parameter
   * @return the shared index informer
   */
  public synchronized <T extends HasMetadata, TList extends KubernetesResourceList<T>> SharedIndexInformer<T> sharedIndexInformerFor(Class<T> apiTypeClass, Class<TList> apiListTypeClass, long resyncPeriodInMillis) {
    return sharedIndexInformerFor(apiTypeClass, apiListTypeClass, context.withPlural(getPluralFromKind(apiTypeClass.getSimpleName())), resyncPeriodInMillis);
  }

  /**
   * Constructs and returns a shared index informer with resync period specified for custom resources.
   *
   * @param customResourceContext basic information about the Custom Resource Definition corresponding to that custom resource
   * @param apiTypeClass apiType class
   * @param apiListTypeClass api list type class
   * @param resyncPeriodInMillis resync period in milliseconds
   * @param <T> the type parameter
   * @param <TList> the type's list parameter
   * @return the shared index informer
   */
  public synchronized <T extends HasMetadata, TList extends KubernetesResourceList<T>> SharedIndexInformer<T> sharedIndexInformerForCustomResource(CustomResourceDefinitionContext customResourceContext, Class<T> apiTypeClass, Class<TList> apiListTypeClass, long resyncPeriodInMillis) {
    return sharedIndexInformerFor(apiTypeClass, apiListTypeClass, context.withApiGroupVersion(customResourceContext.getVersion())
      .withApiGroupName(customResourceContext.getGroup())
      .withPlural(customResourceContext.getPlural()), resyncPeriodInMillis);
  }

  /**
   * Constructs and returns a shared index informer with resync period specified. And the
   * informer cache will be overwritten.
   *
   * @param apiTypeClass apiType class
   * @param apiListTypeClass api list type class
   * @param operationContext operation context
   * @param resyncPeriodInMillis resync period in milliseconds
   * @param <T> the type parameter
   * @param <TList> the type's list parameter
   * @return the shared index informer
   */
  public synchronized <T extends HasMetadata, TList extends KubernetesResourceList<T>> SharedIndexInformer<T> sharedIndexInformerFor(Class<T> apiTypeClass, Class<TList> apiListTypeClass, OperationContext operationContext, long resyncPeriodInMillis) {
    ListerWatcher<T, TList> listerWatcher = listerWatcherFor(apiTypeClass, apiListTypeClass);
    SharedIndexInformer<T> informer = new DefaultSharedIndexInformer<T, TList>(apiTypeClass, listerWatcher, resyncPeriodInMillis, operationContext);
    this.informers.put(apiTypeClass, informer);
    return informer;
  }

  private <T extends HasMetadata, TList extends KubernetesResourceList<T>> ListerWatcher<T, TList> listerWatcherFor(Class<T> apiTypeClass, Class<TList> apiListTypeClass) {

    return new ListerWatcher<T, TList>() {
      @Override
      public TList list(ListOptions params, String namespace, OperationContext context) throws KubernetesClientException {
        BaseOperation<T, TList, ?, ?> listBaseOperation = baseOperation.newInstance(context.withNamespace(namespace));
        listBaseOperation.setType(apiTypeClass);
        listBaseOperation.setListType(apiListTypeClass);

        return listBaseOperation.list();
      }

      @Override
      public Watch watch(ListOptions params, String namespace, OperationContext context, Watcher<T> resourceWatcher) throws KubernetesClientException {
        BaseOperation<T, TList, ?, ?> watchBaseOperation = baseOperation.newInstance(context);
        watchBaseOperation.setType(apiTypeClass);
        watchBaseOperation.setListType(apiListTypeClass);

        return watchBaseOperation.watch(params.getResourceVersion(), resourceWatcher, true);
      }
    };
  }

  /**
   * Gets existing shared index informer, return null if the requesting informer
   * is never constructed.
   *
   * @param apiTypeClass API type class
   * @param <T> type of API type
   * @return SharedIndexInformer object
   */
  public synchronized <T> SharedIndexInformer<T> getExistingSharedIndexInformer(Class<T> apiTypeClass) {
    return this.informers.get(apiTypeClass);
  }

  /**
   * Starts all registered informers.
   */
  public synchronized void startAllRegisteredInformers() {
    if (informers == null || informers.isEmpty()) {
      return;
    }

    informers.forEach(
      (informerType, informer) -> {
        if (!startedInformers.containsKey(informerType)) {
          startedInformers.put(informerType, informerExecutor.submit(informer::run));
        }
      });
  }

  /**
   * Stop all registered informers.
   */
  public synchronized void stopAllRegisteredInformers() {
    if (informers == null || informers.isEmpty()) {
      return;
    }
    informers.forEach(
      (informerType, informer) -> {
        if (startedInformers.containsKey(informerType)) {
          startedInformers.remove(informerType);
          informer.stop();
        }
      });
    informerExecutor.shutdown();
  }
}
