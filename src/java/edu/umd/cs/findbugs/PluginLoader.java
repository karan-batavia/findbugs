/*
 * FindBugs - Find bugs in Java programs
 * Copyright (C) 2003,2004 University of Maryland
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edu.umd.cs.findbugs;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.Node;
import org.dom4j.io.SAXReader;

/**
 * Loader for a FindBugs plugin.
 * A plugin is a jar file containing two metadata files,
 * "findbugs.xml" and "messages.xml".  Those files specify
 * <ul>
 * <li> the bug pattern Detector classes,
 * <li> the bug patterns detected (including all text for displaying
 * detected instances of those patterns), and
 * <li> the "bug codes" which group together related bug instances
 * </ul>
 *
 * <p> The PluginLoader creates a Plugin object to store
 * the Detector factories and metadata.</p>
 *
 * @author David Hovemeyer
 * @see Plugin
 * @see PluginException
 */
public class PluginLoader extends URLClassLoader {

	// Keep a count of how many plugins we've seen without a
	// "pluginid" attribute, so we can assign them all unique ids.
	private static int nextUnknownId;

	// The loaded Plugin
	private Plugin plugin;

	/**
	 * Constructor.
	 *
	 * @param url the URL of the plugin Jar file
	 * @throws PluginException if the plugin cannot be fully loaded
	 */
	public PluginLoader(URL url) throws PluginException {
		super(new URL[]{url});
	}

	/**
	 * Constructor.
	 *
	 * @param url    the URL of the plugin Jar file
	 * @param parent the parent classloader
	 */
	public PluginLoader(URL url, ClassLoader parent) throws PluginException {
		super(new URL[]{url}, parent);
		init();
	}

	/**
	 * Get the Plugin.
	 * @throws PluginException if the plugin cannot be fully loaded
	 */
	public Plugin getPlugin() throws PluginException {
		if (plugin == null)
			init();
		return plugin;
	}
	
	private void init() throws PluginException {
		// Plugin descriptor (a.k.a, "findbugs.xml").  Defines
		// the bug detectors and bug patterns that the plugin provides.
		Document pluginDescriptor;
		
		// Unique plugin id
		String pluginId;

		// List of message translation files in decreasing order of precedence
		ArrayList<Document> messageCollectionList = new ArrayList<Document>();

		// Read the plugin descriptor
		try {
			URL descriptorURL = findResource("findbugs.xml");
			if (descriptorURL == null)
				throw new PluginException("Couldn't find \"findbugs.xml\" in plugin");

			SAXReader reader = new SAXReader();
			pluginDescriptor = reader.read(descriptorURL);
		} catch (DocumentException e) {
			throw new PluginException("Couldn't parse \"findbugs.xml\"", e);
		}
		
		// Get the unique plugin id (or generate one, if none is present)
		pluginId = pluginDescriptor.valueOf("/FindbugsPlugin/@pluginid");
		if (pluginId.equals("")) {
			synchronized (PluginLoader.class) {
				pluginId = "plugin" + nextUnknownId++;
			}
		}

		// See if the plugin is enabled or disabled by default.
		// Note that if there is no "defaultenabled" attribute,
		// then we assume that the plugin IS enabled by default.
		String defaultEnabled = pluginDescriptor.valueOf("/FindbugsPlugin/@defaultenabled");
		boolean pluginEnabled = defaultEnabled.equals("") ? true : Boolean.valueOf(defaultEnabled);

		// Load the message collections
		try {
			Locale locale = Locale.getDefault();
			String language = locale.getLanguage();
			String country = locale.getCountry();

			if (country != null)
				addCollection(messageCollectionList, "messages_" + language + "_" + country + ".xml");
			addCollection(messageCollectionList, "messages_" + language + ".xml");
			addCollection(messageCollectionList, "messages.xml");
		} catch (DocumentException e) {
			e.printStackTrace();
			throw new PluginException("Couldn't parse \"messages.xml\"", e);
		}
		
		// Create the Plugin object (but don't assign to the plugin field yet,
		// since we're still not sure if everything will load correctly)
		Plugin plugin = new Plugin(pluginId);
		plugin.setEnabled(pluginEnabled);

		// Create a DetectorFactory for all Detector nodes
		HashMap<String, DetectorFactory> detectorFactoryMap = new HashMap<String, DetectorFactory>();
		try {
			List detectorNodeList = pluginDescriptor.selectNodes("/FindbugsPlugin/Detector");
			for (Iterator i = detectorNodeList.iterator(); i.hasNext();) {
				Node detectorNode = (Node) i.next();
				String className = detectorNode.valueOf("@class");
				String speed = detectorNode.valueOf("@speed");
				String disabled = detectorNode.valueOf("@disabled");
				String reports = detectorNode.valueOf("@reports");
				String requireJRE = detectorNode.valueOf("@requirejre");
	
				//System.out.println("Found detector: class="+className+", disabled="+disabled);
	
				Class detectorClass = loadClass(className);
				DetectorFactory factory = new DetectorFactory(
						plugin,
						detectorClass, !disabled.equals("true"),
				        speed, reports, requireJRE);
				plugin.addDetectorFactory(factory);
				detectorFactoryMap.put(className, factory);

				// Find Detector node in one of the messages files,
				// to get the detail HTML.
				Node node = findMessageNode(messageCollectionList,
				        "/MessageCollection/Detector[@class='" + className + "']/Details",
				        "Missing Detector description for detector " + className);

				Element details = (Element) node;
				String detailHTML = details.getText();
				StringBuffer buf = new StringBuffer();
				buf.append("<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EN\">\n");
				buf.append("<HTML><HEAD><TITLE>Detector Description</TITLE></HEAD><BODY>\n");
				buf.append(detailHTML);
				buf.append("</BODY></HTML>\n");
				factory.setDetailHTML(buf.toString());
			}
		} catch (ClassNotFoundException e) {
			throw new PluginException("Could not instantiate detector class: " + e, e);
		}

		// Create BugPatterns
		List bugPatternNodeList = pluginDescriptor.selectNodes("/FindbugsPlugin/BugPattern");
		for (Iterator i = bugPatternNodeList.iterator(); i.hasNext();) {
			Node bugPatternNode = (Node) i.next();
			String type = bugPatternNode.valueOf("@type");
			String abbrev = bugPatternNode.valueOf("@abbrev");
			String category = bugPatternNode.valueOf("@category");
			String experimental = bugPatternNode.valueOf("@experimental");

			// Find the matching element in messages.xml (or translations)
			String query = "/MessageCollection/BugPattern[@type='" + type + "']";
			Node messageNode = findMessageNode(messageCollectionList, query,
			        "messages.xml missing BugPattern element for type " + type);

			String shortDesc = getChildText(messageNode, "ShortDescription");
			String longDesc = getChildText(messageNode, "LongDescription");
			String detailText = getChildText(messageNode, "Details");

			BugPattern bugPattern = new BugPattern(type, abbrev, category,
			        Boolean.valueOf(experimental).booleanValue(),
			        shortDesc, longDesc, detailText);
			plugin.addBugPattern(bugPattern);
		}

		// Create BugCodes
		HashSet<String> definedBugCodes = new HashSet<String>();
		for (Iterator<Document> i = messageCollectionList.iterator(); i.hasNext();) {
			Document messageCollection = i.next();

			List bugCodeNodeList = messageCollection.selectNodes("/MessageCollection/BugCode");
			for (Iterator j = bugCodeNodeList.iterator(); j.hasNext();) {
				Node bugCodeNode = (Node) j.next();
				String abbrev = bugCodeNode.valueOf("@abbrev");
				if (abbrev.equals(""))
					throw new PluginException("BugCode element with missing abbrev attribute");
				if (definedBugCodes.contains(abbrev))
					continue;
				String description = bugCodeNode.getText();
				BugCode bugCode = new BugCode(abbrev, description);
				plugin.addBugCode(bugCode);
				definedBugCodes.add(abbrev);
			}

		}
		
		// Success!
		// Assign to the plugin field, so getPlugin() can return the
		// new Plugin object.
		this.plugin = plugin;

	}

	private void addCollection(List<Document> messageCollectionList, String filename)
	        throws DocumentException {
		URL messageURL = findResource(filename);
		if (messageURL != null) {
			SAXReader reader = new SAXReader();
			Document messageCollection = reader.read(messageURL);
			messageCollectionList.add(messageCollection);
		}
	}

	private static Node findMessageNode(List<Document> messageCollectionList, String xpath,
	                                    String missingMsg) throws PluginException {

		for (Iterator<Document> i = messageCollectionList.iterator(); i.hasNext();) {
			Document document = i.next();
			Node node = document.selectSingleNode(xpath);
			if (node != null)
				return node;
		}
		throw new PluginException(missingMsg);
	}

	private static String getChildText(Node node, String childName) throws PluginException {
		Node child = node.selectSingleNode(childName);
		if (child == null)
			throw new PluginException("Could not find child \"" + childName + "\" for node");
		return child.getText();
	}

}

// vim:ts=4
