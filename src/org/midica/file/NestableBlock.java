/*
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. 
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.midica.file;

import java.util.ArrayList;

/**
 * This class represents a nestable block, used by the MidicaPL parser.
 * 
 * @author Jan Trukenmüller
 */
public class NestableBlock {
	
	private MidicaPLParser    parser   = null;
	private boolean           multiple = false;
	private int               quantity = 1;
	private ArrayList<Object> elements = null;
	
	/**
	 * Creates a new nestable block.
	 * 
	 * @param parser      the parser object that was responsible to create the block
	 * @param multiple    indicates if the channel tickstamps are reverted at the end of the block
	 * @param quantity    indicates how often the block is executed
	 */
	public NestableBlock(MidicaPLParser parser, boolean multiple, int quantity) {
		this.parser   = parser;
		this.multiple = multiple;
		this.quantity = quantity;
		
		elements = new ArrayList<Object>();
	}
	
	/**
	 * Adds a new content element to this block.
	 * The content to be added can be one of the following objects:
	 * 
	 * - an array of strings belonging to one source code line
	 * - another (nested) block
	 * 
	 * @param element the content to be added
	 */
	public void add(Object element) {
		
		// tokens?
		if (element instanceof String[]) {
			element = String.join(" ", (String[]) element);
		}
		
		elements.add(element);
	}
	
	/**
	 * Executes the content of the block.
	 * 
	 * @throws ParseException if one of the content lines cannot be parsed.
	 */
	public void play() throws ParseException {
		
		// remember current tickstamps if needed
		ArrayList<Long> tickstamps = null;
		if (multiple) {
			tickstamps = parser.rememberTickstamps();
		}
		
		// apply the block content
		for (int i = 0; i < quantity; i++) {
			for (Object element : elements) {
				if (element instanceof NestableBlock) {
					((NestableBlock) element).play();
				}
				else if (element instanceof String) {
					parser.parseLine((String) element);
				}
				else {
					throw new ParseException("invalid block element class: " + element);
				}
			}
		}
		
		// restore tickstamps, if needed
		if (multiple) {
			parser.restoreTickstamps(tickstamps);
		}
	}
	
	/**
	 * Returns a string representation of the block contents.
	 * This is mainly for debugging.
	 */
	public String toString() {
		if (null == elements) {
			return "null";
		}
		return "\n{q" + quantity + "\n" + elements.toString() + "\n}\n";
	}
}