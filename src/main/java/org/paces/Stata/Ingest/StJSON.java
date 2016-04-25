package org.paces.Stata.Ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.io.*;
import java.util.*;
import static org.paces.Stata.Ingest.NodeUtils.*;

/**
 * Class that creates a flattened representation of JSON data.  Although this
 * can be used a bit more broadly, the intented use is to create a flattened
 * representation of the JSON that could then be mapped onto a two
 * dimensional space a bit more easily (e.g., convert the map object into a
 * single row vector of values, a two dimensional array with a column vector
 * of strings and column vector of values, etc...).  Flattening the JSON
 * object was the first needed step in this process.  Additionally, this
 * class will also provide query functions that are more robust and
 * convenient than the JSONPath standard by using notation used for *nix
 * based file systems (e.g.,
 * /element/child/grandchild_#/greatgrandchild/etc...) where a number
 * appended to an element name indicates both the order and the unique value
 * from the JSON (these values are index starting from 1).
 *
 * @author Billy Buchanan
 * @version 0.0.0
 */
public class StJSON implements Serializable {

	/**
	 * Member containing the generation strings in the order they exist in
	 * the JSON
	 */
	private LinkedList<String> generations = new LinkedList<>();

	/**
	 * Member containing strings to map individual datum to Stata types.
	 */
	private LinkedList<String> nodeTypes = new LinkedList<>();

	/**
	 * Member containing key/value pairs where the key is the full
	 * path/generation string to a specific element and the value is the
	 * JsonNode datum
	 */
	private Map<String, JsonNode> jsonData;

	/**
	 * Class constructor for the StJSON type
	 * @param node A JsonNode object that will be flattened/parsed
	 */
	public StJSON(JsonNode node) {
		this(node, 0, new LinkedList<String>());
	}

	/**
	 * Class constructor for the StJSON type
	 * @param node A JsonNode object that will be flattened/parsed
	 * @param currentLevel The current depth of the descent through the
	 *                        JsonNode elements
	 */
	public StJSON(JsonNode node, Integer currentLevel) {
		this(node, currentLevel, new LinkedList<String>());
	}

	/**
	 * Class constructor for the StJSON type
	 * @param node A JsonNode object that will be flattened/parsed
	 * @param currentLevel The current depth of the descent through the
	 *                        JsonNode elements
	 * @param parent A LinkedList of String objects that contain the names of
	 *                  each of the parent generations related to the node.
	 */
	public StJSON(JsonNode node, Integer currentLevel, LinkedList<String> parent) {
		try {
			this.jsonData = flatten(node, currentLevel, parent);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Public method used handle recasting ObjectNode types to JsonNode types
	 * to overload the descent method
	 * @param node An JsonNode which will be descended into to determine
	 *                the depth of nested objects
	 * @param currentLevel The level of the descent where this node occurs
	 * @param parent A string containing the field name for the node; the
	 *                  first level should always be named root.  Beyond
	 *                  this, the format of this string will be:
	 *               root/child_#/grandchild_#/greatgrandchild_#/etc...
	 *               Where the number appended to the string indicates the
	 *               depth of the specific node
	 * @return a Map object containing the JsonNodes keyed by the generation
	 * string.
	 */
	public Map<String, JsonNode> flatten(JsonNode node, Integer currentLevel,
	                       LinkedList<String> parent) throws IOException {

		// Creates a list of child nodes given the JsonNode passed to this
		// method
		List<JsonNode> children = nodeList(node);

		// Creates a list of field/node names given the JsonNode passed to
		// this method
		List<String> fieldNames = fieldList(node);

		// The Map object that will hold the flattened key value pairs
		Map<String, JsonNode> nodeMap = new LinkedHashMap<>();

		// Container for string that holds the generation information of the
		// nodes
		LinkedList<String> lineage = new LinkedList<>();

		// Adds the values passed to the method
		lineage.addAll(parent);

		// Sets the current iteration depth.  This gets used to identify
		// repeated objects within container nodes and to make sure the key
		// values are unique across all nodes
		Integer depth = currentLevel;

		// Iterate over the child nodes based on their numeric indices
		for(Integer i = 0; i < children.size(); i++) {

			// Create incremented node ID
			Integer nodeID = i + 1;

			// Checks to see if the current node is an array and if so,
			// whether or not it contains child elements that are container
			// types
			if (node.isArray() && containsContainers(children.get(i))) {

				// If so, starts recursion into the child elements
				nodeMap.putAll(flatten(children.get(i), depth, checkName(lineage, nodeID)));

			// If the child element is an ArrayNode type but has no elements
			// in the Array
			} else if (!children.get(i).elements().hasNext() && children.get(i).isArray()) {

				// This is a terminal node (e.g., Empty Array)
				// Add it to the stack
				nodeMap.putAll(processTerminalNode(lineage, fieldNames.get(i),
													children.get(i), depth + 1));

			// If the child object contains nodes that are container nodes
			// Or if the child node is an Array and does not have container
			// child nodes of its own
			} else if (containsContainers(children.get(i)) ||
			(children.get(i).isArray() && !containsContainers(children.get(i)))) {

				// Recurse into the child element
				nodeMap.putAll(flatten(children.get(i), depth, checkName(lineage, fieldNames.get(i))));

				// Remove the last generation pushed to the lineage stack
				lineage.pop();

			// If the child object is an ObjectNode type and does not contain
			// any container nodes as children (e.g., each node with in
			// the ObjectNode is a terminal node
			} else if (children.get(i).isObject() && !containsContainers(children.get(i))) {

				// Recurse into the ObjectNode
				nodeMap.putAll(flatten(children.get(i), depth, checkName(lineage, fieldNames.get(i), depth)));

				// Remove the last generation pushed to the lineage stack
				lineage.pop();

			// If this is a terminal node
			} else  {

				// String object used to hold the name of this field
				String field;

				// If there are values in the fieldNames object, get the
				// appropriate field name from that list
				if (fieldNames.size() > 0) field = fieldNames.get(i);

				// Otherwise, it is an array so use the generic element_# to
				// ID the individual elements within the Array
				else field = "element_" + nodeID.toString();

				// Add this key/value pair to the map object
				nodeMap.putAll(processTerminalNode(lineage, field, children.get(i), depth + 1));

			} // End ELSE Block for terminal nodes

		} // End Loop over the nodes in this JsonNode object

		// Returns the map object containing all of the values
		return nodeMap;

	} // End of Method declaration

	/**
	 * Checks to see if the node is completely empty and returns a missing
	 * node in place of a null value if it is.
	 * @param jn The JsonNode object to inspect
	 * @return An object of type JsonNode, which can be a MissingNode type
	 * object or any other JsonNode type object.
	 */
	protected JsonNode checkEmptyNodes(JsonNode jn) {
		if ((jn.isArray() || jn.isObject()) && jn.size() == 0) return MissingNode.getInstance();
		else return jn;
	}

	/**
	 * Creates the Map object that contains a key value pair of the full node
	 * lineage and the node object
	 * @param generations A LinkedList of Strings that holds the descent
	 *                       lineage through the JSON object
	 * @param thisField The name of this terminal node field
	 * @param theNode The object containing the datum
	 * @param depth the descent depth of the current node used to add numeric
	 *                 ID to root level nodes
	 * @return A Map where the key contains the descent tree with the field
	 * name appended to it.
	 */
	protected Map<String, JsonNode> processTerminalNode(LinkedList<String> generations,
                                                       String thisField,
                                                       JsonNode theNode,
	                                                   Integer depth) {

		// Creates a new Map object used to create a key/value pair object
		Map<String, JsonNode> nodeMap = new HashMap<>();

		// Creates the generation string which will have the field name appended
		String genString = makeGenerationString(generations.descendingIterator());

		// If the generation name isn't empty, add the path delimiter before
		// the field name as the key and add the JsonNode object as the value
		if (!genString.isEmpty()) {
			nodeMap.put(genString + "/" + thisField, checkEmptyNodes(theNode));
			this.generations.add(genString + "/" + thisField);
			setTypeMap(checkEmptyNodes(theNode));
		}

		// Otherwise add the field name with a root delimiter before it
		else {
			nodeMap.put("/" + thisField + "_" + depth.toString(), checkEmptyNodes(theNode));
			this.generations.add("/" + thisField + "_" + depth.toString());
			setTypeMap(checkEmptyNodes(theNode));
		}

		return nodeMap;

	}

	/**
	 * Method used to process and create the generation string used to identify
	 * the lineage of a terminal node
	 * @param iter An iterator created from the parent list object (use the
	 *                descendingIterator() method on the parent list object).
	 * @return Returns a String object with all the generations with path
	 * delimiters separating them
	 */
	public String makeGenerationString(Iterator<String> iter) {

		// Create a new string joiner object
		StringJoiner genString = new StringJoiner("/");

		// As long as there are more values
		while(iter.hasNext()) {

			// Get the next string value
			String var = iter.next();

			// If it isn't empty add it to the string joiner object
			if (!var.isEmpty()) genString.add(var);

		} // End While loop over string iterator

		// Convert the string joiner to a string and return it
		if (!genString.toString().isEmpty()) return "/" + genString.toString();

		// If the generation string is empty just return the empty string
		else return genString.toString();

	} // End Method declaration

	/**
	 * Method used to implement logic related to lineage of data element
	 * being represented in the LinkedList of string elements
	 * @param lineage The LinkedList of String elements containing the field
	 *                   names from all generations prior to the current
	 *                   generation.
	 * @param fieldName The name of the field to add to the generation string
	 * @return Returns the LinkedList with the appropriate handling of the
	 * new field name being added to the stack.
	 */
	private LinkedList<String> checkName(LinkedList<String> lineage,
	                                     String fieldName) {

		// Checks to see if the list contains any data
		if (lineage.size() > 0) {

			// Pops the top name off the stack and removes any depth
			// indicators so they can be replaced with current value
			String curgen = lineage.pop();

			// If the previous field name is not the same as this one, push it
			// back onto the stack
			if (!curgen.replaceAll("(.*)(_[0-9]+)$", "$1").equals(fieldName)) lineage.push(curgen);

			// Add the field name to the stack
			lineage.push(fieldName);

		} // End IF Block when there are elements in the list

		// Adds field name to the list
		else lineage.push(fieldName);

		// Returns the generation string container
		return lineage;

	} // End Method declaration

	/**
	 * Method used to implement logic related to lineage of data element
	 * being represented in the LinkedList of string elements
	 * @param lineage The LinkedList of String elements containing the field
	 *                   names from all generations prior to the current
	 *                   generation.
	 * @param fieldName The name of the field to add to the generation string
	 * @param depth An Integer value indicating the depth of the descent into
	 *                 the JSON Tree
	 * @return Returns the LinkedList with the appropriate handling of the
	 * new field name being added to the stack.
	 */
	private LinkedList<String> checkName(LinkedList<String> lineage,
	                                            String fieldName,
	                                            Integer depth) {

		// Test the side of the generation string container
		if (lineage.size() > 0) {

			// Pops the top name off the stack and removes any depth
			// indicators so they can be replaced with current value
			String curgen = lineage.pop();

			// If the previous generation name is not the same as the current
			// field name, push the previous generation name back onto the stack
			if (!curgen.replaceAll("(.*)(_[0-9]+)$", "$1").equals(fieldName)) lineage.push(curgen);

			// If it was the same name, just add the field name
			lineage.push(fieldName);

		} // End IF Block for pre-populated generation string

		// If the generation string has no values, add the depth value to the
		// field name and add it to the generation string
		else lineage.push(fieldName + "_" + depth.toString());

		// Return the generation string container
		return lineage;

	} // End of Method declaration

	/**
	 * Method used to implement logic related to lineage of data element
	 * being represented in the LinkedList of string elements
	 * @param lineage The LinkedList of String elements containing the field
	 *                   names from all generations prior to the current
	 *                   generation.
	 * @param nodeID An integer value used to identify container elements
	 *                  stored in an array object
	 * @return Returns the LinkedList with the appropriate handling of the
	 * new field name being added to the stack.
	 */
	private LinkedList<String> checkName(LinkedList<String> lineage, Integer nodeID) {

		// Pops the top name off the stack and removes any depth
		// indicators so they can be replaced with current value
		String curgen = lineage.pop().replaceAll("(.*)(_[0-9]+)$", "$1");

		// If the previous generation name isn't equal to the generation
		// prior to it, push it back onto the stack with a new numeric ID
		if (!curgen.equals(lineage.peek())) lineage.push(curgen + "_" + nodeID.toString());

		// If it is the same it gets pushed back onto the stack without the
		// numeric ID value appended
		else lineage.push(curgen);

		// Return the generation string container
		return lineage;

	} // End of Method declaration

	/**
	 * Method that returns the keys for the underlying Map object
	 * @return A Set containing all of the Map object's Key Elements
	 */
	public Set<String> keySet() {
		return this.jsonData.keySet();
	}

	/**
	 * Method used to retrieve individual JSON Datum
	 * @param key The key used to identify the node to retrieve
	 * @return A JSON Node object identified by the string passed to the method
	 */
	public JsonNode get(String key) {
		return this.jsonData.get(key);
	}

	/**
	 * Method used to get the list of generation strings that can be used for
	 * search/query operations.
	 * @return A LinkedList of String types
	 */
	public LinkedList<String> getLineage() {
		return this.generations;
	}

	/**
	 * Method used to map the JsonNodeTypes onto types that can be created in
	 * Stata.  Both Ints and Longs in Java are equivalent to long types in
	 * Stata.  A short type in Java (e.g., 2-byte integer) is equivalent to
	 * an int in Stata.  Booleans are mapped to bytes and will have
	 * true/false converted to binary indicators.
	 * @param value
	 */
	protected void setTypeMap(JsonNode value) {
		if (value.isBigDecimal()) this.nodeTypes.add("double");
		else if (value.isBigInteger()) this.nodeTypes.add("double");
		else if (value.isBinary()) this.nodeTypes.add("strl");
		else if (value.isBoolean()) this.nodeTypes.add("byte");
		else if (value.isDouble() ) this.nodeTypes.add("double");
		else if (value.isFloat()) this.nodeTypes.add("double");
		else if (value.isFloatingPointNumber()) this.nodeTypes.add("double");
		else if (value.isInt()) this.nodeTypes.add("long");
		else if (value.isIntegralNumber()) this.nodeTypes.add("double");
		else if (value.isLong()) this.nodeTypes.add("double");
		else if (value.isMissingNode()) this.nodeTypes.add("missing");
		else if (value.isNull()) this.nodeTypes.add("missing");
		else if (value.isNumber()) this.nodeTypes.add("double");
		else if (value.isObject()) this.nodeTypes.add("strl");
		else if (value.isPojo()) this.nodeTypes.add("strl");
		else if (value.isShort()) this.nodeTypes.add("int");
		else if (value.isTextual()) this.nodeTypes.add("str");
		else this.nodeTypes.add("unknown");
	}

	/**
	 * Method to access the nodeTypes member
	 * @return A LinkedList of String types that identify the Stata type
	 * mapping for the element.
	 */
	public LinkedList<String> getTypeMap() {
		return this.nodeTypes;
	}

}
