/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.portal.osgi.web.wab.extender.internal;

import com.liferay.portal.kernel.util.ArrayUtil;
import com.liferay.portal.kernel.util.HashMapDictionary;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.osgi.web.servlet.context.helper.ServletContextHelperRegistration;
import com.liferay.portal.osgi.web.servlet.jsp.compiler.JspServlet;
import com.liferay.portal.osgi.web.wab.extender.internal.adapter.FilterExceptionAdapter;
import com.liferay.portal.osgi.web.wab.extender.internal.adapter.ModifiableServletContext;
import com.liferay.portal.osgi.web.wab.extender.internal.adapter.ModifiableServletContextAdapter;
import com.liferay.portal.osgi.web.wab.extender.internal.adapter.ServletContextListenerExceptionAdapter;
import com.liferay.portal.osgi.web.wab.extender.internal.adapter.ServletExceptionAdapter;
import com.liferay.portal.osgi.web.wab.extender.internal.definition.FilterDefinition;
import com.liferay.portal.osgi.web.wab.extender.internal.definition.ListenerDefinition;
import com.liferay.portal.osgi.web.wab.extender.internal.definition.ServletDefinition;
import com.liferay.portal.osgi.web.wab.extender.internal.definition.WebXMLDefinition;
import com.liferay.portal.osgi.web.wab.extender.internal.definition.WebXMLDefinitionLoader;

import java.io.IOException;
import java.io.InputStream;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import java.net.URL;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Dictionary;
import java.util.EventListener;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

import javax.servlet.Filter;
import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextAttributeListener;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestAttributeListener;
import javax.servlet.ServletRequestListener;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.HandlesTypes;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionListener;

import javax.xml.parsers.SAXParserFactory;

import org.apache.felix.utils.log.Logger;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;

/**
 * @author Raymond Augé
 * @author Miguel Pastor
 */
public class WabBundleProcessor {

	public WabBundleProcessor(Bundle bundle, Logger logger) {
		_bundle = bundle;
		_logger = logger;

		BundleWiring bundleWiring = _bundle.adapt(BundleWiring.class);

		_bundleClassLoader = bundleWiring.getClassLoader();
		_bundleContext = _bundle.getBundleContext();
	}

	public void destroy() throws Exception {
		Thread currentThread = Thread.currentThread();

		ClassLoader contextClassLoader = currentThread.getContextClassLoader();

		try {
			currentThread.setContextClassLoader(_bundleClassLoader);

			destroyServlets();

			destroyFilters();

			destroyListeners();

			_servletContextRegistration.unregister();

			_bundleContext.ungetService(
				_servletContextHelperRegistrationServiceReference);
		}
		finally {
			currentThread.setContextClassLoader(contextClassLoader);
		}
	}

	public void init(
			SAXParserFactory saxParserFactory,
			Dictionary<String, Object> properties)
		throws Exception {

		Thread currentThread = Thread.currentThread();

		ClassLoader contextClassLoader = currentThread.getContextClassLoader();

		try {
			currentThread.setContextClassLoader(_bundleClassLoader);

			WebXMLDefinitionLoader webXMLDefinitionLoader =
				new WebXMLDefinitionLoader(_bundle, saxParserFactory, _logger);

			WebXMLDefinition webXMLDefinition =
				webXMLDefinitionLoader.loadWebXML();

			ServletContextHelperRegistration servletContextHelperRegistration =
				initContext(
					webXMLDefinition.getContextParameters(),
					webXMLDefinition.getJspTaglibMappings());

			boolean wabShapedBundle =
				servletContextHelperRegistration.isWabShapedBundle();

			if (!wabShapedBundle) {
				return;
			}

			ServletContext servletContext =
				ModifiableServletContextAdapter.createInstance(
					servletContextHelperRegistration.getServletContext(),
					_bundle.getBundleContext(), webXMLDefinition, _logger);

			initServletContainerInitializers(_bundle, servletContext);

			scanTLDsForListeners(webXMLDefinition, servletContext);

			initListeners(
				webXMLDefinition.getListenerDefinitions(), servletContext);

			ModifiableServletContext modifiableServletContext =
				(ModifiableServletContext)servletContext;

			initListeners(
				modifiableServletContext.getListenerDefinitions(),
				servletContext);

			modifiableServletContext.registerFilters();

			initFilters(webXMLDefinition.getFilterDefinitions());

			modifiableServletContext.registerServlets();

			initServlets(webXMLDefinition.getServletDefinitions());
		}
		catch (Exception e) {
			_logger.log(
				Logger.LOG_ERROR,
				"Catastrophic initialization failure! Shutting down " +
					_contextName + " WAB due to: " + e.getMessage(),
				e);

			destroy();

			throw e;
		}
		finally {
			currentThread.setContextClassLoader(contextClassLoader);
		}
	}

	public static class JspServletWrapper extends HttpServlet {

		public JspServletWrapper(String jspFile) {
			this.jspFile = jspFile;
		}

		@Override
		public void destroy() {
			_servlet.destroy();
		}

		@Override
		public ServletConfig getServletConfig() {
			return _servlet.getServletConfig();
		}

		@Override
		public void init(ServletConfig servletConfig) throws ServletException {
			_servlet.init(servletConfig);
		}

		@Override
		public void service(
				ServletRequest servletRequest, ServletResponse servletResponse)
			throws IOException, ServletException {

			String curJspFile = (String)servletRequest.getAttribute(
				JspServlet.JSP_FILE);

			if (jspFile != null) {
				servletRequest.setAttribute(JspServlet.JSP_FILE, jspFile);
			}

			try {
				_servlet.service(servletRequest, servletResponse);
			}
			finally {
				servletRequest.setAttribute(JspServlet.JSP_FILE, curJspFile);
			}
		}

		protected String jspFile;

		private final Servlet _servlet = new JspServlet();

	}

	protected void collectAnnotatedClasses(
		String classResource, Bundle bundle, Class<?>[] handledTypesArray,
		Set<Class<?>> annotatedClasses) {

		String className = classResource.replaceAll("\\.class$", "");

		className = className.replaceAll("/", ".");

		Class<?> annotatedClass = null;

		try {
			annotatedClass = bundle.loadClass(className);
		}
		catch (Throwable t) {
			_logger.log(Logger.LOG_DEBUG, t.getMessage());

			return;
		}

		// Class extends/implements

		for (Class<?> handledType : handledTypesArray) {
			if (handledType.isAssignableFrom(annotatedClass) &&
				!Modifier.isAbstract(annotatedClass.getModifiers())) {

				annotatedClasses.add(annotatedClass);

				return;
			}
		}

		// Class annotation

		Annotation[] classAnnotations = new Annotation[0];

		try {
			classAnnotations = annotatedClass.getAnnotations();
		}
		catch (Throwable t) {
			_logger.log(Logger.LOG_DEBUG, t.getMessage());
		}

		for (Annotation classAnnotation : classAnnotations) {
			if (ArrayUtil.contains(
					handledTypesArray, classAnnotation.annotationType())) {

				annotatedClasses.add(annotatedClass);

				return;
			}
		}

		// Method annotation

		Method[] classMethods = new Method[0];

		try {
			classMethods = annotatedClass.getDeclaredMethods();
		}
		catch (Throwable t) {
			_logger.log(Logger.LOG_DEBUG, t.getMessage());
		}

		for (Method method : classMethods) {
			Annotation[] methodAnnotations = new Annotation[0];

			try {
				methodAnnotations = method.getDeclaredAnnotations();
			}
			catch (Throwable t) {
				_logger.log(Logger.LOG_DEBUG, t.getMessage());
			}

			for (Annotation methodAnnotation : methodAnnotations) {
				if (ArrayUtil.contains(
						handledTypesArray, methodAnnotation.annotationType())) {

					annotatedClasses.add(annotatedClass);

					return;
				}
			}
		}

		// Field annotation

		Field[] declaredFields = new Field[0];

		try {
			declaredFields = annotatedClass.getDeclaredFields();
		}
		catch (Throwable t) {
			_logger.log(Logger.LOG_DEBUG, t.getMessage());
		}

		for (Field field : declaredFields) {
			Annotation[] fieldAnnotations = new Annotation[0];

			try {
				fieldAnnotations = field.getDeclaredAnnotations();
			}
			catch (Throwable t) {
				_logger.log(Logger.LOG_DEBUG, t.getMessage());
			}

			for (Annotation fieldAnnotation : fieldAnnotations) {
				if (ArrayUtil.contains(
						handledTypesArray, fieldAnnotation.annotationType())) {

					annotatedClasses.add(annotatedClass);

					return;
				}
			}
		}
	}

	protected void destroyFilters() {
		for (ServiceRegistration<?> serviceRegistration :
				_filterRegistrations) {

			try {
				serviceRegistration.unregister();
			}
			catch (Exception e) {
				_logger.log(Logger.LOG_ERROR, e.getMessage(), e);
			}
		}

		_filterRegistrations.clear();
	}

	protected void destroyListeners() {
		for (ServiceRegistration<?> serviceRegistration :
				_listenerRegistrations) {

			try {
				serviceRegistration.unregister();
			}
			catch (Exception e) {
				_logger.log(Logger.LOG_ERROR, e.getMessage(), e);
			}
		}

		_listenerRegistrations.clear();
	}

	protected void destroyServlets() {
		for (ServiceRegistration<?> serviceRegistration :
				_servletRegistrations) {

			try {
				serviceRegistration.unregister();
			}
			catch (Exception e) {
				_logger.log(Logger.LOG_ERROR, e.getMessage(), e);
			}
		}

		_servletRegistrations.clear();
	}

	protected String[] getClassNames(EventListener eventListener) {
		List<String> classNamesList = new ArrayList<>();

		if (HttpSessionAttributeListener.class.isInstance(eventListener)) {
			classNamesList.add(HttpSessionAttributeListener.class.getName());
		}

		if (HttpSessionListener.class.isInstance(eventListener)) {
			classNamesList.add(HttpSessionListener.class.getName());
		}

		if (ServletContextAttributeListener.class.isInstance(eventListener)) {
			classNamesList.add(ServletContextAttributeListener.class.getName());
		}

		// The following supported listener is omitted on purpose because it is
		// registered individually.

		/*
		if (ServletContextListener.class.isInstance(eventListener)) {
			classNamesList.add(ServletContextListener.class.getName());
		}
		*/

		if (ServletRequestAttributeListener.class.isInstance(eventListener)) {
			classNamesList.add(ServletRequestAttributeListener.class.getName());
		}

		if (ServletRequestListener.class.isInstance(eventListener)) {
			classNamesList.add(ServletRequestListener.class.getName());
		}

		return classNamesList.toArray(new String[classNamesList.size()]);
	}

	protected ServletContextHelperRegistration initContext(
		Map<String, String> contextParameters,
		Map<String, String> jspTaglibMappings) {

		_servletContextHelperRegistrationServiceReference =
			_bundleContext.getServiceReference(
				ServletContextHelperRegistration.class);

		ServletContextHelperRegistration servletContextHelperRegistration =
			_bundleContext.getService(
				_servletContextHelperRegistrationServiceReference);

		servletContextHelperRegistration.setProperties(contextParameters);

		ServletContext servletContext =
			servletContextHelperRegistration.getServletContext();

		_contextName = servletContext.getServletContextName();

		servletContext.setAttribute("jsp.taglib.mappings", jspTaglibMappings);
		servletContext.setAttribute("osgi-bundlecontext", _bundleContext);
		servletContext.setAttribute("osgi-runtime-vendor", _VENDOR);

		Dictionary<String, Object> properties = new HashMapDictionary<>();

		properties.put("osgi.web.symbolicname", _bundle.getSymbolicName());
		properties.put("osgi.web.version", _bundle.getVersion());
		properties.put("osgi.web.contextpath", servletContext.getContextPath());

		_servletContextRegistration = _bundleContext.registerService(
			ServletContext.class, servletContext, properties);

		return servletContextHelperRegistration;
	}

	protected void initFilters(Map<String, FilterDefinition> filterDefinitions)
		throws Exception {

		for (Map.Entry<String, FilterDefinition> entry :
				filterDefinitions.entrySet()) {

			FilterDefinition filterDefinition = entry.getValue();

			Dictionary<String, Object> properties = new Hashtable<>();

			properties.put(
				HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT,
				_contextName);
			properties.put(
				HttpWhiteboardConstants.
					HTTP_WHITEBOARD_FILTER_ASYNC_SUPPORTED,
				filterDefinition.isAsyncSupported());
			properties.put(
				HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_DISPATCHER,
				filterDefinition.getDispatchers());
			properties.put(
				HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_NAME,
				filterDefinition.getName());
			properties.put(
				HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_PATTERN,
				filterDefinition.getURLPatterns());
			properties.put(
				HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_SERVLET,
				filterDefinition.getServletNames());
			properties.put(
				Constants.SERVICE_RANKING, filterDefinition.getPriority());

			Map<String, String> initParameters =
				filterDefinition.getInitParameters();

			for (Entry<String, String> initParametersEntry :
					initParameters.entrySet()) {

				String key = initParametersEntry.getKey();
				String value = initParametersEntry.getValue();

				properties.put(
					HttpWhiteboardConstants.
						HTTP_WHITEBOARD_FILTER_INIT_PARAM_PREFIX + key,
					value);
			}

			FilterExceptionAdapter filterExceptionAdaptor =
				new FilterExceptionAdapter(filterDefinition.getFilter());

			ServiceRegistration<Filter> serviceRegistration =
				_bundleContext.registerService(
					Filter.class, filterExceptionAdaptor, properties);

			Exception exception = filterExceptionAdaptor.getException();

			if (exception != null) {
				serviceRegistration.unregister();

				throw exception;
			}

			_filterRegistrations.add(serviceRegistration);
		}
	}

	protected void initListeners(
			List<ListenerDefinition> listenerDefinitions,
			ServletContext servletContext)
		throws Exception {

		for (ListenerDefinition listenerDefinition : listenerDefinitions) {
			Dictionary<String, Object> properties = new Hashtable<>();

			properties.put(
				HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT,
				_contextName);
			properties.put(
				HttpWhiteboardConstants.HTTP_WHITEBOARD_LISTENER,
				Boolean.TRUE.toString());

			String[] classNames = getClassNames(
				listenerDefinition.getEventListener());

			if (classNames.length > 0) {
				ServiceRegistration<?> serviceRegistration =
					_bundleContext.registerService(
						classNames, listenerDefinition.getEventListener(),
						properties);

				_listenerRegistrations.add(serviceRegistration);
			}

			if (!ServletContextListener.class.isInstance(
					listenerDefinition.getEventListener())) {

				continue;
			}

			ServletContextListenerExceptionAdapter
				servletContextListenerExceptionAdaptor =
					new ServletContextListenerExceptionAdapter(
						(ServletContextListener)
							listenerDefinition.getEventListener(),
						servletContext);

			ServiceRegistration<?> serviceRegistration =
				_bundleContext.registerService(
					ServletContextListener.class,
					servletContextListenerExceptionAdaptor, properties);

			Exception exception =
				servletContextListenerExceptionAdaptor.getException();

			if (exception != null) {
				serviceRegistration.unregister();

				throw exception;
			}

			_listenerRegistrations.add(serviceRegistration);
		}
	}

	protected void initServletContainerInitializers(
		Bundle bundle, ServletContext servletContext) {

		BundleWiring bundleWiring = bundle.adapt(BundleWiring.class);

		Collection<String> initializerResources = bundleWiring.listResources(
			"META-INF/services", "javax.servlet.ServletContainerInitializer",
			BundleWiring.LISTRESOURCES_RECURSE);

		if (initializerResources == null) {
			return;
		}

		for (String initializerResource : initializerResources) {
			URL url = bundle.getResource(initializerResource);

			if (url == null) {
				continue;
			}

			try (InputStream inputStream = url.openStream()) {
				String fqcn = StringUtil.read(inputStream);

				processServletContainerInitializerClass(
					fqcn, bundle, bundleWiring, servletContext);
			}
			catch (IOException ioe) {
				_logger.log(Logger.LOG_ERROR, ioe.getMessage(), ioe);
			}
		}
	}

	protected void initServlets(
			Map<String, ServletDefinition> servletDefinitions)
		throws Exception {

		for (Entry<String, ServletDefinition> entry :
				servletDefinitions.entrySet()) {

			ServletDefinition servletDefinition = entry.getValue();

			Dictionary<String, Object> properties = new Hashtable<>();

			properties.put(
				HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT,
				_contextName);
			properties.put(
				HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_ASYNC_SUPPORTED,
				servletDefinition.isAsyncSupported());
			properties.put(
				HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_ERROR_PAGE,
				servletDefinition.getErrorPages());
			properties.put(
				HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME,
				servletDefinition.getName());

			String jspFile = servletDefinition.getJspFile();
			List<String> urlPatterns = servletDefinition.getURLPatterns();

			if (urlPatterns.isEmpty() && (jspFile != null)) {
				urlPatterns.add(jspFile);
			}

			properties.put(
				HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN,
				urlPatterns);

			Map<String, String> initParameters =
				servletDefinition.getInitParameters();

			for (Entry<String, String> initParametersEntry :
					initParameters.entrySet()) {

				String key = initParametersEntry.getKey();
				String value = initParametersEntry.getValue();

				properties.put(
					HttpWhiteboardConstants.
						HTTP_WHITEBOARD_SERVLET_INIT_PARAM_PREFIX + key,
					value);
			}

			ServletExceptionAdapter servletExceptionAdaptor =
				new ServletExceptionAdapter(servletDefinition.getServlet());

			ServiceRegistration<Servlet> serviceRegistration =
				_bundleContext.registerService(
					Servlet.class, servletExceptionAdaptor, properties);

			Exception exception = servletExceptionAdaptor.getException();

			if (exception != null) {
				serviceRegistration.unregister();

				throw exception;
			}

			_servletRegistrations.add(serviceRegistration);
		}
	}

	protected void processServletContainerInitializerClass(
		String fqcn, Bundle bundle, BundleWiring bundleWiring,
		ServletContext servletContext) {

		Class<? extends ServletContainerInitializer> initializerClass = null;

		try {
			Class<?> clazz = bundle.loadClass(fqcn);

			if (!ServletContainerInitializer.class.isAssignableFrom(clazz)) {
				return;
			}

			initializerClass = clazz.asSubclass(
				ServletContainerInitializer.class);
		}
		catch (Exception e) {
			_logger.log(Logger.LOG_ERROR, e.getMessage(), e);

			return;
		}

		HandlesTypes handledTypes = initializerClass.getAnnotation(
			HandlesTypes.class);

		if (handledTypes == null) {
			handledTypes = _NULL_HANDLES_TYPES;
		}

		Class<?>[] handledTypesArray = handledTypes.value();

		if (handledTypesArray == null) {
			handledTypesArray = new Class[0];
		}

		Collection<String> classResources = bundleWiring.listResources(
			"/", "*.class", BundleWiring.LISTRESOURCES_RECURSE);

		if (classResources == null) {
			classResources = new ArrayList<>(0);
		}

		Set<Class<?>> annotatedClasses = new HashSet<>();

		for (String classResource : classResources) {
			URL urlClassResource = bundle.getResource(classResource);

			if (urlClassResource == null) {
				continue;
			}

			collectAnnotatedClasses(
				classResource, bundle, handledTypesArray, annotatedClasses);
		}

		if (annotatedClasses.isEmpty()) {
			annotatedClasses = null;
		}

		try {
			ServletContainerInitializer servletContainerInitializer =
				initializerClass.newInstance();

			servletContainerInitializer.onStartup(
				annotatedClasses, servletContext);
		}
		catch (Throwable t) {
			_logger.log(Logger.LOG_ERROR, t.getMessage(), t);
		}
	}

	protected void scanTLDsForListeners(
		WebXMLDefinition webXMLDefinition, ServletContext servletContext) {

		List<String> listenerClassNames = new ArrayList<>();

		JspServlet.scanTLDs(_bundle, servletContext, listenerClassNames);

		for (String listenerClassName : listenerClassNames) {
			try {
				Class<?> clazz = _bundle.loadClass(listenerClassName);

				Class<? extends EventListener> eventListenerClass =
					clazz.asSubclass(EventListener.class);

				EventListener eventListener = eventListenerClass.newInstance();

				ListenerDefinition listenerDefinition =
					new ListenerDefinition();

				listenerDefinition.setEventListener(eventListener);

				webXMLDefinition.addListenerDefinition(listenerDefinition);
			}
			catch (Exception e) {
				_logger.log(
					Logger.LOG_ERROR,
					"Bundle " + _bundle + " is unable to load listener " +
						listenerClassName);
			}
		}
	}

	private static final HandlesTypes _NULL_HANDLES_TYPES = new HandlesTypes() {

		@Override
		public Class<? extends Annotation> annotationType() {
			return null;
		}

		@Override
		public Class<?>[] value() {
			return new Class[0];
		}

	};

	private static final String _VENDOR = "Liferay, Inc.";

	private final Bundle _bundle;
	private final ClassLoader _bundleClassLoader;
	private final BundleContext _bundleContext;
	private String _contextName;
	private final Set<ServiceRegistration<Filter>> _filterRegistrations =
		new ConcurrentSkipListSet<>();
	private final Set<ServiceRegistration<?>> _listenerRegistrations =
		new ConcurrentSkipListSet<>();
	private final Logger _logger;
	private ServiceReference<ServletContextHelperRegistration>
		_servletContextHelperRegistrationServiceReference;
	private ServiceRegistration<ServletContext> _servletContextRegistration;
	private final Set<ServiceRegistration<Servlet>> _servletRegistrations =
		new ConcurrentSkipListSet<>();

}