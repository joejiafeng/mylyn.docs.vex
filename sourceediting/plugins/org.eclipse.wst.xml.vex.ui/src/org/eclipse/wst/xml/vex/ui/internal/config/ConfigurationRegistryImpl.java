/*******************************************************************************
 * Copyright (c) 2004, 2008 John Krasnay and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     John Krasnay - initial API and implementation
 *     Igor Jacy Lino Campista - Java 5 warnings fixed (bug 311325)
 *******************************************************************************/
package org.eclipse.wst.xml.vex.ui.internal.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.ILock;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.swt.widgets.Display;
import org.eclipse.wst.xml.vex.core.internal.core.ListenerList;

/**
 * Singleton registry of configuration sources and listeners.
 * 
 * The configuration sources may be accessed by multiple threads, and are
 * protected by a lock. All methods that modify or iterate over config sources
 * do so after acquiring the lock. Callers that wish to perform multiple
 * operations as an atomic transaction must lock and unlock the registry as
 * follows.
 * 
 * <pre>
 * ConfigRegistry reg = ConfigRegistry.getInstance();
 * try {
 * 	reg.lock();
 * 	// make modifications
 * } finally {
 * 	reg.unlock();
 * }
 * </pre>
 * 
 * <p>
 * This class also maintains a list of ConfigListeners. The addConfigListener
 * and removeConfigListener methods must be called from the main UI thread. The
 * fireConfigXXX methods may be called from other threads; this class will
 * ensure the listeners are called on the UI thread.
 */
public class ConfigurationRegistryImpl implements ConfigurationRegistry {

	private volatile ConfigLoaderJob loaderJob = null;
	private volatile boolean loaded = false;

	public ConfigurationRegistryImpl() {
		configItemFactories.add(new DoctypeFactory());
		configItemFactories.add(new StyleFactory());
		ResourcesPlugin.getWorkspace().addResourceChangeListener(resourceChangeListener);
	}

	public void dispose() {
		ResourcesPlugin.getWorkspace().removeResourceChangeListener(resourceChangeListener);
	}

	public void loadConfigurations() {
		lock();
		try {
			loaderJob = new ConfigLoaderJob();
			loaderJob.addJobChangeListener(new JobChangeAdapter() {
				@Override
				public void done(final IJobChangeEvent event) {
					lock();
					try {
						configs = new HashMap<String, ConfigSource>();
						for (final ConfigSource configSource : loaderJob.getAllConfigSources())
							configs.put(configSource.getUniqueIdentifer(), configSource);
						loaded = true;
						loaderJob = null;
					} finally {
						unlock();
					}
					fireConfigLoaded(new ConfigEvent(ConfigurationRegistryImpl.this));
				}
			});
			loaderJob.schedule();
		} finally {
			unlock();
		}
	}

	/**
	 * Removes all loaded ConfigSource instances and resets the configLoaded
	 * flag.
	 */
	public void clear() {
		try {
			lock();
			configs = new HashMap<String, ConfigSource>();
			loaded = false;
		} finally {
			unlock();
		}
	}

	/**
	 * Add a ConfigSource to the list of configurations.
	 * 
	 * @param config
	 *            ConfigSource to be added.
	 */
	public void addConfigSource(final ConfigSource config) {
		try {
			lock();
			configs.put(config.getUniqueIdentifer(), config);
		} finally {
			unlock();
		}
	}

	/**
	 * Call the configChanged method on all registered ConfigChangeListeners.
	 * 
	 * @param e
	 *            ConfigEvent to be fired.
	 */
	public void fireConfigChanged(final ConfigEvent e) {
		configListeners.fireEvent("configChanged", e); //$NON-NLS-1$
	}

	/**
	 * Call the configLoaded method on all registered ConfigChangeListeners.
	 * This method is called from the ConfigLoaderJob
	 * thread.
	 * 
	 * @param e
	 *            ConfigEvent to be fired.
	 */
	public void fireConfigLoaded(final ConfigEvent e) {
		configListeners.fireEvent("configLoaded", e); //$NON-NLS-1$
	}

	/**
	 * Returns an array of all registered ConfigItem objects implementing the
	 * given extension point.
	 * 
	 * @param extensionPointId
	 *            ID of the desired extension point.
	 */
	private List<ConfigItem> getAllConfigItems(final String extensionPointId) {
		try {
			lock();
			final List<ConfigItem> result = new ArrayList<ConfigItem>();
			for (final ConfigSource config : configs.values())
				result.addAll(config.getValidItems(extensionPointId));
			return result;
		} finally {
			unlock();
		}
	}

	private List<ConfigSource> getAllConfigSources() {
		waitUntilLoaded();
		try {
			lock();
			final List<ConfigSource> result = new ArrayList<ConfigSource>();
			result.addAll(configs.values());
			return result;
		} finally {
			unlock();
		}
	}

	private void waitUntilLoaded() {
		if (!loaded)
			if (loaderJob == null)
				throw new IllegalStateException("The configurations are not loaded yet. Call 'loadConfigurations' first.");
			else
				try {
					loaderJob.join();
				} catch (final InterruptedException e) {
					Thread.currentThread().interrupt();
				}
	}

	/**
	 * Returns a specific configuration item given an extension point id and the
	 * item's id. Returns null if either the extension point or the item is not
	 * found.
	 * 
	 * @param extensionPoint
	 *            ID of the desired extension point.
	 * @param id
	 *            ID of the desired item.
	 */
	public ConfigItem getConfigItem(final String extensionPoint, final String id) {
		try {
			lock();
			for (final ConfigItem item : getAllConfigItems(extensionPoint))
				if (item.getUniqueId().equals(id))
					return item;
			return null;
		} finally {
			unlock();
		}
	}

	/**
	 * Returns the IConfigItemFactory object for the given extension point or
	 * null if none exists.
	 * 
	 * @param extensionPointId
	 *            Extension point ID for which to search.
	 */
	public IConfigItemFactory getConfigItemFactory(final String extensionPointId) {
		for (final IConfigItemFactory factory : configItemFactories)
			if (factory.getExtensionPointId().equals(extensionPointId))
				return factory;
		return null;
	}

	/**
	 * Returns true if the Vex configuration has been loaded.
	 * 
	 * @see org.eclipse.wst.xml.vex.ui.internal.config.ConfigLoaderJob
	 */
	public boolean isLoaded() {
		return loaded;
	}

	/**
	 * Remove a VexConfiguration from the list of configs.
	 * 
	 * @param config
	 *            VexConfiguration to remove.
	 */
	public void removeConfigSource(final ConfigSource config) {
		try {
			lock();
			configs.remove(config);
		} finally {
			unlock();
		}
	}

	/**
	 * Adds a ConfigChangeListener to the notification list.
	 * 
	 * @param listener
	 *            Listener to be added.
	 */
	public void addConfigListener(final IConfigListener listener) {
		configListeners.add(listener);
	}

	/**
	 * Removes a ConfigChangeListener from the notification list.
	 * 
	 * @param listener
	 *            Listener to be removed.
	 */
	public void removeConfigListener(final IConfigListener listener) {
		configListeners.remove(listener);
	}

	/**
	 * Locks the registry for modification or iteration over its config sources.
	 */
	public void lock() {
		lock.acquire();
	}

	/**
	 * Unlocks the registry.
	 */
	public void unlock() {
		lock.release();
	}

	// new interface

	/**
	 * The document type configuration for the given public identifier, of null
	 * if there is no configuration for the given public identifier.
	 * 
	 * @param publicId
	 *            the public identifier
	 * @return the document type configuration for the given public identifier,
	 *         of null if there is no configuration for the given public
	 *         identifier.
	 */
	public DocumentType getDocumentType(final String publicId) {
		final List<ConfigItem> configItems = getAllConfigItems(DocumentType.EXTENSION_POINT);
		for (final ConfigItem configItem : configItems) {
			final DocumentType doctype = (DocumentType) configItem;
			if (doctype.getPublicId().equals(publicId))
				return doctype;
		}
		return null;
	}

	public DocumentType[] getDocumentTypes() {
		final List<DocumentType> result = new ArrayList<DocumentType>();
		for (final ConfigItem configItem : getAllConfigItems(DocumentType.EXTENSION_POINT))
			result.add((DocumentType) configItem);
		return result.toArray(new DocumentType[result.size()]);
	}

	/**
	 * Return a list of document types for which there is at least one
	 * registered style.
	 * 
	 * @return a list of document types for which there is at least one
	 *         registered style.
	 */
	public DocumentType[] getDocumentTypesWithStyles() {
		final List<DocumentType> result = new ArrayList<DocumentType>();
		for (final ConfigItem configItem : getAllConfigItems(DocumentType.EXTENSION_POINT)) {
			final DocumentType doctype = (DocumentType) configItem;
			if (getStyles(doctype.getPublicId()).length > 0)
				result.add(doctype);
		}
		return result.toArray(new DocumentType[result.size()]);
	}

	public Style[] getStyles(final String publicId) {
		final ArrayList<Style> result = new ArrayList<Style>();
		for (final ConfigItem configItem : getAllConfigItems(Style.EXTENSION_POINT)) {
			final Style style = (Style) configItem;
			if (style.appliesTo(publicId))
				result.add(style);
		}
		return result.toArray(new Style[result.size()]);
	}

	public Style getStyle(final String styleId) {
		for (final ConfigItem configItem : getAllConfigItems(Style.EXTENSION_POINT)) {
			final Style style = (Style) configItem;
			if (style.getUniqueId().equals(styleId))
				return style;
		}
		return null;
	}

	public Style getStyle(final String publicId, final String preferredStyleId) {
		final Style[] styles = getStyles(publicId);
		if (styles.length == 0)
			return null;
		if (preferredStyleId != null)
			for (final Style style : styles)
				if (style.getUniqueId().equals(preferredStyleId))
					return style;
		return styles[0];
	}

	/**
	 * Factory method that returns the plugin project for the given IProject. If
	 * the given project does not have the Vex plugin project nature, null is
	 * returned. PluginProject instances are cached so they can be efficiently
	 * returned.
	 * 
	 * @param project
	 *            IProject for which to return the PluginProject.
	 * @return the corresponding PluginProject
	 */
	public PluginProject getPluginProject(final IProject project) {
		for (final ConfigSource source : getAllConfigSources())
			if (source instanceof PluginProject) {
				final PluginProject pluginProject = (PluginProject) source;
				if (project.equals(pluginProject.getProject()))
					return pluginProject;
			}
		return null;
	}

	// ======================================================== PRIVATE

	private final ILock lock = Job.getJobManager().newLock();
	private Map<String, ConfigSource> configs = new HashMap<String, ConfigSource>();
	private final ListenerList<IConfigListener, ConfigEvent> configListeners = new ListenerList<IConfigListener, ConfigEvent>(IConfigListener.class);
	private final List<IConfigItemFactory> configItemFactories = new ArrayList<IConfigItemFactory>();

	private final IResourceChangeListener resourceChangeListener = new IResourceChangeListener() {
		public void resourceChanged(final IResourceChangeEvent event) {

			// System.out.println("resourceChanged, type is " + event.getType()
			// + ", resource is " + event.getResource());

			if (event.getType() == IResourceChangeEvent.PRE_CLOSE || event.getType() == IResourceChangeEvent.PRE_DELETE) {
				final PluginProject pluginProject = getPluginProject((IProject) event.getResource());
				if (pluginProject != null) {
					// System.out.println("  removing project from config registry");
					removeConfigSource(pluginProject);
					fireConfigChanged(new ConfigEvent(this));
				}
			} else if (event.getType() == IResourceChangeEvent.POST_CHANGE) {
				final IResourceDelta[] resources = event.getDelta().getAffectedChildren();
				for (final IResourceDelta delta : resources)
					if (delta.getResource() instanceof IProject) {
						final IProject project = (IProject) delta.getResource();

						// System.out.println("Project " + project.getName() +
						// " changed, isOpen is " + project.isOpen());

						final PluginProject pluginProject = getPluginProject(project);

						boolean hasPluginProjectNature = false;
						try {
							hasPluginProjectNature = project.hasNature(PluginProjectNature.ID);
						} catch (final CoreException ex) {
							// yup, sometimes checked exceptions really blow
						}

						if (!project.isOpen() && pluginProject != null) {

							// System.out.println("  closing project: " +
							// project.getName());
							removeConfigSource(pluginProject);
							fireConfigChanged(new ConfigEvent(this));

						} else if (project.isOpen() && pluginProject == null && hasPluginProjectNature) {

							// System.out.println("  newly opened project: " +
							// project.getName() + ", rebuilding");

							// Must be run in another thread, since the
							// workspace is locked here
							final Runnable runnable = new Runnable() {
								public void run() {
									PluginProject.load(project);
								}
							};
							Display.getDefault().asyncExec(runnable);
						} else {
							// System.out.println("  no action taken");
						}
					}
			}
		}
	};

}
