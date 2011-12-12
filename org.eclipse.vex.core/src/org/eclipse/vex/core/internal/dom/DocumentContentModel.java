/*******************************************************************************
 * Copyright (c) 2011 Florian Thienel and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 * 		Florian Thienel - initial API and implementation
 *******************************************************************************/
package org.eclipse.vex.core.internal.dom;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.MessageFormat;

import org.eclipse.wst.common.uriresolver.internal.provisional.URIResolver;
import org.eclipse.wst.common.uriresolver.internal.provisional.URIResolverPlugin;
import org.eclipse.wst.xml.core.internal.contentmodel.CMDocument;
import org.eclipse.wst.xml.core.internal.contentmodel.ContentModelManager;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * @author Florian Thienel
 */
public class DocumentContentModel implements EntityResolver {

	private static final URIResolver URI_RESOLVER = URIResolverPlugin.createResolver();

	private String baseUri;
	private String publicId;
	private String systemId;
	private String schemaId;
	
	public DocumentContentModel() {
	}
	
	public DocumentContentModel(final String baseUri, final String publicId, final String systemId, final RootElement rootElement) {
		initialize(baseUri, publicId, systemId, rootElement);
	}

	public void initialize(final String baseUri, final String publicId, final String systemId, final RootElement rootElement) {
		this.baseUri = baseUri;
		this.publicId = publicId;
		this.systemId = systemId;
		if (rootElement != null)
			schemaId = rootElement.getQualifiedName().getQualifier();
		else
			schemaId = null;
	}

	public String getMainDocumentTypeIdentifier() {
		if (publicId != null)
			return publicId;
		if (systemId != null)
			return systemId;
		return schemaId;
	}

	public boolean isDtdAssigned() {
		return publicId != null || systemId != null;
	}
	
	public CMDocument getDTD() {
		if (publicId != null) {
			final URL dtdUrl = resolveSchemaIdentifier(publicId);
			if (dtdUrl != null)
				return createCMDocument(dtdUrl.toString());
		}
		if (systemId != null)
			return createCMDocument(systemId);
		return null;
	}

	private CMDocument createCMDocument(String resolved) {
		final ContentModelManager modelManager = ContentModelManager.getInstance();
		return modelManager.createCMDocument(resolved, null);
	}

	public IWhitespacePolicy getWhitespacePolicy() {
		return IWhitespacePolicy.NULL;
	}

	public InputSource resolveEntity(String publicId, String systemId) throws SAXException, IOException {
		final String resolved = URI_RESOLVER.resolve(baseUri, publicId, systemId);
		System.out.println("Resolved " + publicId + " " + systemId + " -> " + resolved);
		if (resolved == null)
			return null;
		
		final InputSource result = new InputSource(resolved);
		result.setPublicId(publicId);
		return result;
	}

	public URL resolveSchemaIdentifier(String schemaIdentifier) {
		final String schemaLocation = URI_RESOLVER.resolve(baseUri, schemaIdentifier, null);
		if (schemaLocation == null)
			/*
			 * TODO this is a common case that should be handled somehow
			 * - a hint should be shown: the schema is not available, the schema
			 * should be added to the catalog by the user
			 * - an inferred schema should be used, to allow to at least display
			 * the document in the editor
			 * - this is not the right place to either check or handle this
			 */
			throw new AssertionError("Cannot resolve schema '" + schemaIdentifier + "'.");
		try {
			return new URL(schemaLocation);
		} catch (MalformedURLException e) {
			throw new AssertionError(MessageFormat.format("Resolution of schema ''{0}'' resulted in a malformed URL: ''{1}''. {2}", schemaIdentifier,
					schemaLocation, e.getMessage()));
		}
	}

}