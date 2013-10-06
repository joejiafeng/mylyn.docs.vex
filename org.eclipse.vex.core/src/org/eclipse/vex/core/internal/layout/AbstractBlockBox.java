/*******************************************************************************
 * Copyright (c) 2004, 2013 John Krasnay and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     John Krasnay - initial API and implementation
 *     Igor Jacy Lino Campista - Java 5 warnings fixed (bug 311325)
 *     Carsten Hiesserich - Fixed layout issue when editing nested blocks (bug 408482)
 *******************************************************************************/
package org.eclipse.vex.core.internal.layout;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.eclipse.vex.core.internal.core.Caret;
import org.eclipse.vex.core.internal.core.Color;
import org.eclipse.vex.core.internal.core.ColorResource;
import org.eclipse.vex.core.internal.core.FontMetrics;
import org.eclipse.vex.core.internal.core.Graphics;
import org.eclipse.vex.core.internal.core.Insets;
import org.eclipse.vex.core.internal.css.CSS;
import org.eclipse.vex.core.internal.css.StyleSheet;
import org.eclipse.vex.core.internal.css.Styles;
import org.eclipse.vex.core.internal.widget.IBoxFilter;
import org.eclipse.vex.core.provisional.dom.BaseNodeVisitorWithResult;
import org.eclipse.vex.core.provisional.dom.ContentRange;
import org.eclipse.vex.core.provisional.dom.IComment;
import org.eclipse.vex.core.provisional.dom.IDocument;
import org.eclipse.vex.core.provisional.dom.IElement;
import org.eclipse.vex.core.provisional.dom.INode;
import org.eclipse.vex.core.provisional.dom.IParent;
import org.eclipse.vex.core.provisional.dom.IPosition;
import org.eclipse.vex.core.provisional.dom.IProcessingInstruction;
import org.eclipse.vex.core.provisional.dom.IText;

/**
 * Base class of block boxes that can contain other block boxes. This class implements the layout method and various
 * navigation methods. Subclasses must implement the createChildren method.
 * 
 * Subclasses can be anonymous or non-anonymous (i.e. generated by an element). Since the vast majority of instances
 * will be non-anonymous, this class can manage the element and top and bottom margins without too much inefficiency.
 * 
 * <p>
 * Subclasses that can be anonymous must override the getStartPosition and getEndPosition classes to return the range
 * covered by the box.
 * </p>
 */
public abstract class AbstractBlockBox extends AbstractBox implements BlockBox {

	/** The length, in pixels, of the horizontal caret between block boxes */
	private static final int H_CARET_LENGTH = 20;

	/**
	 * Element with which we are associated. For anonymous boxes, this is null.
	 */
	private final INode node;

	/*
	 * We cache the top and bottom margins, since they may be affected by our children.
	 */
	private int marginTop;
	private int marginBottom;

	/**
	 * Start position of an anonymous box. For non-anonymous boxes, this is null.
	 */
	private IPosition startPosition;

	/**
	 * End position of an anonymous box. For non-anonymous boxes, this is null.
	 */
	private IPosition endPosition;

	/**
	 * Class constructor for non-anonymous boxes.
	 * 
	 * @param context
	 *            LayoutContext being used.
	 * @param parent
	 *            Parent box.
	 * @param node
	 *            Node associated with this box. anonymous box.
	 */
	public AbstractBlockBox(final LayoutContext context, final BlockBox parent, final INode node) {
		this.parent = parent;
		this.node = node;

		final Styles styles = context.getStyleSheet().getStyles(node);
		final int parentWidth = parent.getWidth();
		marginTop = styles.getMarginTop().get(parentWidth);
		marginBottom = styles.getMarginBottom().get(parentWidth);

	}

	/**
	 * Class constructor for anonymous boxes.
	 * 
	 * @param context
	 *            LayoutContext to use.
	 * @param parent
	 *            Parent box.
	 * @param startOffset
	 *            Start of the range covered by the box.
	 * @param endOffset
	 *            End of the range covered by the box.
	 */
	public AbstractBlockBox(final LayoutContext context, final BlockBox parent, final int startOffset, final int endOffset) {
		this.parent = parent;
		node = null;
		marginTop = 0;
		marginBottom = 0;

		final IDocument doc = context.getDocument();
		startPosition = doc.createPosition(startOffset);
		endPosition = doc.createPosition(endOffset);
	}

	/**
	 * Walks the box tree and returns the nearest enclosing element.
	 */
	protected IParent findContainingParent() {
		BlockBox box = this;
		INode node = box.getNode();
		while (!(node instanceof IParent)) {
			box = box.getParent();
			node = box.getNode();
		}
		return (IParent) node;
	}

	/**
	 * Returns this box's children as an array of BlockBoxes.
	 */
	protected BlockBox[] getBlockChildren() {
		return (BlockBox[]) getChildren();
	}

	@Override
	public Caret getCaret(final LayoutContext context, final int offset) {

		// If we haven't yet laid out this block, estimate the caret.
		if (getLayoutState() != LAYOUT_OK) {
			final int relative = offset - getStartOffset();
			final int size = getEndOffset() - getStartOffset();
			int y = 0;
			if (size > 0) {
				y = getHeight() * relative / size;
			}
			return new HCaret(0, y, getHCaretWidth());
		}

		int y;

		final Box[] children = getContentChildren();
		for (int i = 0; i < children.length; i++) {

			if (offset < children[i].getStartOffset()) {
				if (i > 0) {
					y = (children[i - 1].getY() + children[i - 1].getHeight() + children[i].getY()) / 2;
				} else {
					y = 0;
				}
				return new HCaret(0, y, getHCaretWidth());
			}

			if (offset >= children[i].getStartOffset() && offset <= children[i].getEndOffset()) {

				final Caret caret = children[i].getCaret(context, offset);
				caret.translate(children[i].getX(), children[i].getY());
				return caret;
			}
		}

		if (hasChildren()) {
			y = getHeight();
		} else {
			y = getHeight() / 2;
		}

		return new HCaret(0, y, getHCaretWidth());
	}

	@Override
	public Box[] getChildren() {
		return children;
	}

	/**
	 * Return an array of children that contain content.
	 */
	protected BlockBox[] getContentChildren() {
		final Box[] children = getChildren();
		final List<BlockBox> result = new ArrayList<BlockBox>(children.length);
		for (final Box child : children) {
			if (child.hasContent()) {
				result.add((BlockBox) child);
			}
		}
		return result.toArray(new BlockBox[result.size()]);
	}

	@Override
	public INode getNode() {
		return node;
	}

	@Override
	public int getEndOffset() {
		final INode element = getNode();
		if (element != null) {
			return element.getEndOffset();
		} else if (getEndPosition() != null) {
			return getEndPosition().getOffset();
		} else {
			throw new IllegalStateException();
		}
	}

	/**
	 * Returns the estimated size of the box, based on the the current font size and the number of characters covered by
	 * the box. This is a utility method that can be used in implementation of setInitialSize. It assumes the width of
	 * the box has already been correctly set.
	 * 
	 * @param context
	 *            LayoutContext to use.
	 */
	protected int getEstimatedHeight(final LayoutContext context) {

		final INode node = findContainingParent();
		final Styles styles = context.getStyleSheet().getStyles(node);
		final int charCount = getEndOffset() - getStartOffset();

		final float fontSize = styles.getFontSize();
		final float lineHeight = styles.getLineHeight();
		final float estHeight = lineHeight * fontSize * 0.6f * charCount / getWidth();

		return Math.round(Math.max(estHeight, lineHeight));
	}

	public LineBox getFirstLine() {
		if (hasChildren()) {
			final BlockBox firstChild = (BlockBox) getChildren()[0];
			return firstChild.getFirstLine();
		} else {
			return null;
		}
	}

	/**
	 * Returns the width of the horizontal caret. This is overridden by TableBox to return a caret that is the full
	 * width of the table.
	 */
	protected int getHCaretWidth() {
		return H_CARET_LENGTH;
	}

	@Override
	public Insets getInsets(final LayoutContext context, final int containerWidth) {

		if (getNode() != null) {
			final Styles styles = context.getStyleSheet().getStyles(getNode());

			final int top = marginTop + styles.getBorderTopWidth() + styles.getPaddingTop().get(containerWidth);

			final int left = styles.getMarginLeft().get(containerWidth) + styles.getBorderLeftWidth() + styles.getPaddingLeft().get(containerWidth);

			final int bottom = marginBottom + styles.getBorderBottomWidth() + styles.getPaddingBottom().get(containerWidth);

			final int right = styles.getMarginRight().get(containerWidth) + styles.getBorderRightWidth() + styles.getPaddingRight().get(containerWidth);

			return new Insets(top, left, bottom, right);
		} else {
			return new Insets(marginTop, 0, marginBottom, 0);
		}
	}

	public LineBox getLastLine() {
		if (hasChildren()) {
			final BlockBox lastChild = (BlockBox) getChildren()[getChildren().length - 1];
			return lastChild.getLastLine();
		} else {
			return null;
		}
	}

	/**
	 * Returns the layout state of this box.
	 */
	public byte getLayoutState() {
		return layoutState;
	}

	public int getLineEndOffset(final int offset) {
		final BlockBox[] children = getContentChildren();
		for (final BlockBox element2 : children) {
			if (element2.containsOffset(offset)) {
				return element2.getLineEndOffset(offset);
			}
		}
		return offset;
	}

	public int getLineStartOffset(final int offset) {
		final BlockBox[] children = getContentChildren();
		for (final BlockBox element2 : children) {
			if (element2.containsOffset(offset)) {
				return element2.getLineStartOffset(offset);
			}
		}
		return offset;
	}

	public int getMarginBottom() {
		return marginBottom;
	}

	public int getMarginTop() {
		return marginTop;
	}

	public int getNextLineOffset(final LayoutContext context, final int offset, final int x) {

		//
		// This algorithm works when this block owns the offsets between
		// its children.
		//

		if (offset == getEndOffset()) {
			return -1;
		}

		final BlockBox[] children = getContentChildren();

		if (offset < getStartOffset() && children.length > 0 && children[0].getStartOffset() > getStartOffset()) {
			//
			// If there's an offset before the first child, put the caret there.
			//
			return getStartOffset();
		}

		for (final BlockBox child : children) {
			if (offset <= child.getEndOffset()) {
				final int newOffset = child.getNextLineOffset(context, offset, x - child.getX());
				if (newOffset < 0 /* && i < children.length-1 */) {
					return child.getEndOffset() + 1;
				} else {
					return newOffset;
				}
			}
		}

		return getEndOffset();
	}

	public BlockBox getParent() {
		return parent;
	}

	public int getPreviousLineOffset(final LayoutContext context, final int offset, final int x) {

		if (offset == getStartOffset()) {
			return -1;
		}

		final BlockBox[] children = getContentChildren();

		if (offset > getEndOffset() && children.length > 0 && children[children.length - 1].getEndOffset() < getEndOffset()) {
			//
			// If there's an offset after the last child, put the caret there.
			//
			return getEndOffset();
		}

		for (int i = children.length; i > 0; i--) {
			final BlockBox child = children[i - 1];
			if (offset >= child.getStartOffset()) {
				final int newOffset = child.getPreviousLineOffset(context, offset, x - child.getX());
				if (newOffset < 0 && i > 0) {
					return child.getStartOffset() - 1;
				} else {
					return newOffset;
				}
			}
		}

		return getStartOffset();
	}

	@Override
	public int getStartOffset() {
		final INode node = getNode();
		if (node != null) {
			return node.getStartOffset() + 1;
		} else if (getStartPosition() != null) {
			return getStartPosition().getOffset();
		} else {
			throw new IllegalStateException();
		}
	}

	@Override
	public boolean hasContent() {
		if (isAnonymous()) {
			return hasChildren();
		}
		return getNode().isAssociated();
	}

	public void invalidate(final boolean direct) {

		if (direct) {
			layoutState = LAYOUT_REDO;
			if (getParent() instanceof AbstractBlockBox) {
				// The parent box may contain text elements after the edited offset, that has to be updated
				((AbstractBlockBox) getParent()).invalidateChildrenAfterOffset(getStartOffset());
			}
		} else if (layoutState != LAYOUT_REDO) {
			layoutState = LAYOUT_PROPAGATE;
		}

		if (getParent() instanceof AbstractBlockBox) {
			((AbstractBlockBox) getParent()).invalidate(false);
		}
	}

	/**
	 * Checks if there are any children with content after an invalidated element. If so, the box has to update those
	 * children.
	 * 
	 * @param offset
	 *            The offset of the edited element
	 */
	protected void invalidateChildrenAfterOffset(final int offset) {
		final IBoxFilter filter = new IBoxFilter() {
			public boolean matches(final Box box) {
				return box.hasContent() && box.getStartOffset() > offset;
			}
		};

		for (final Box child : getChildren()) {
			if (filter.matches(child)) {
				layoutState = LAYOUT_REDO;
				break;
			}
		}
	}

	@Override
	public boolean isAnonymous() {
		return getNode() == null;
	}

	/**
	 * Call the given callback for each child matching one of the given display styles. Any nodes that do not match one
	 * of the given display types cause the onRange callback to be called, with a range covering all such contiguous
	 * nodes.
	 * 
	 * @param styleSheet
	 *            StyleSheet from which to determine display styles.
	 * @param displayStyles
	 *            Display types to be explicitly recognized.
	 * @param callback
	 *            DisplayStyleCallback through which the caller is notified of matching elements and non-matching
	 *            ranges.
	 */
	protected void iterateChildrenByDisplayStyle(final StyleSheet styleSheet, final Set<String> displayStyles, final ElementOrRangeCallback callback) {
		LayoutUtils.iterateChildrenByDisplayStyle(styleSheet, displayStyles, findContainingParent(), getStartOffset(), getEndOffset(), callback);
	}

	@Override
	public void paint(final LayoutContext context, final int x, final int y) {

		if (skipPaint(context, x, y)) {
			return;
		}

		final boolean drawBorders = !context.isNodeSelected(getNode());

		this.drawBox(context, x, y, getParent().getWidth(), drawBorders);

		paintChildren(context, x, y);

		paintSelectionFrame(context, x, y, true);
	}

	/**
	 * Default implementation. Width is calculated as the parent's width minus this box's insets. Height is calculated
	 * by getEstimatedHeight.
	 */
	public void setInitialSize(final LayoutContext context) {
		final int parentWidth = getParent().getWidth();
		final Insets insets = this.getInsets(context, parentWidth);
		setWidth(Math.max(0, parentWidth - insets.getLeft() - insets.getRight()));
		setHeight(getEstimatedHeight(context));
	}

	@Override
	public int viewToModel(final LayoutContext context, final int x, final int y) {
		final Box[] children = getChildren();

		if (children == null) {
			final int charCount = getEndOffset() - getStartOffset() - 1;
			if (charCount == 0 || getHeight() == 0) {
				return getEndOffset();
			}

			return getStartOffset() + charCount * y / getHeight();
		}

		for (final Box child : children) {
			if (!child.hasContent()) {
				continue;
			}

			if (y < child.getY()) {
				return child.getStartOffset() - 1;
			}

			if (y < child.getY() + child.getHeight()) {
				return child.viewToModel(context, x - child.getX(), y - child.getY());
			}

		}

		return getEndOffset();
	}

	// ===================================================== PRIVATE

	private final BlockBox parent;
	private Box[] children;

	/**
	 * Paint a frame that indicates a block element box has been selected.
	 * 
	 * @param context
	 *            LayoutContext to use.
	 * @param x
	 *            x-coordinate at which to draw
	 * @param y
	 *            y-coordinate at which to draw.
	 * @param selected
	 */
	protected void paintSelectionFrame(final LayoutContext context, final int x, final int y, final boolean selected) {

		final INode node = getNode();
		final IParent parent = node == null ? null : node.getParent();

		final boolean paintFrame = context.isNodeSelected(node) && !context.isNodeSelected(parent);

		if (!paintFrame) {
			return;
		}

		final Graphics g = context.getGraphics();
		ColorResource foreground;
		ColorResource background;

		if (selected) {
			foreground = g.getSystemColor(ColorResource.SELECTION_FOREGROUND);
			background = g.getSystemColor(ColorResource.SELECTION_BACKGROUND);
		} else {
			foreground = g.createColor(new Color(0, 0, 0));
			background = g.createColor(new Color(0xcc, 0xcc, 0xcc));
		}

		final FontMetrics fm = g.getFontMetrics();
		final ColorResource oldColor = g.setColor(background);
		g.setLineStyle(Graphics.LINE_SOLID);
		g.setLineWidth(1);
		final String frameName = getSelectionFrameName(node);
		final int tabWidth = g.stringWidth(frameName) + fm.getLeading();
		final int tabHeight = fm.getHeight();
		final int tabX = x + getWidth() - tabWidth;
		final int tabY = y + getHeight() - tabHeight;
		g.drawRect(x, y, getWidth(), getHeight());
		g.fillRect(tabX, tabY, tabWidth, tabHeight);
		g.setColor(foreground);
		g.drawString(frameName, tabX + fm.getLeading() / 2, tabY);

		g.setColor(oldColor);
		if (!selected) {
			foreground.dispose();
			background.dispose();
		}
	}

	protected String getSelectionFrameName(final INode node) {
		return node.accept(new BaseNodeVisitorWithResult<String>() {
			@Override
			public String visit(final IElement element) {
				return element.getPrefixedName();
			}

			@Override
			public String visit(final IComment comment) {
				return "Comment";
			}

			@Override
			public String visit(final IProcessingInstruction comment) {
				return "Processing instruction";
			}
		});
	}

	/** Layout is OK */
	public static final byte LAYOUT_OK = 0;

	/** My layout is OK, but one of my children needs to be laid out */
	public static final byte LAYOUT_PROPAGATE = 1;

	/** I need to be laid out */
	public static final byte LAYOUT_REDO = 2;

	private byte layoutState = LAYOUT_REDO;

	public VerticalRange layout(final LayoutContext context, final int top, final int bottom) {

		VerticalRange repaintRange = null;
		boolean repaintToBottom = false;
		final int originalHeight = getHeight();

		if (layoutState == LAYOUT_REDO) {

			// System.out.println("Redo layout of " +
			// this.getElement().getName());

			final List<Box> childList = createChildren(context);
			children = childList.toArray(new BlockBox[childList.size()]);

			// Even though we position children after layout, we have to
			// do a preliminary positioning here so we now which ones
			// overlap our layout band
			for (final Box element2 : children) {
				final BlockBox child = (BlockBox) element2;
				child.setInitialSize(context);
			}
			positionChildren(context);

			// repaint everything
			repaintToBottom = true;
			repaintRange = new VerticalRange(0, 0);
		}

		final Box[] children = getChildren();
		for (final Box element2 : children) {
			if (element2 instanceof BlockBox) {
				final BlockBox child = (BlockBox) element2;
				if (top <= child.getY() + child.getHeight() && bottom >= child.getY()) {

					final VerticalRange layoutRange = child.layout(context, top - child.getY(), bottom - child.getY());
					if (layoutRange != null) {
						if (repaintRange == null) {
							repaintRange = layoutRange.moveBy(child.getY());
						} else {
							repaintRange = repaintRange.union(layoutRange.moveBy(child.getY()));
						}
					}
				}
			}
		}

		final int childRepaintStart = positionChildren(context);
		if (childRepaintStart != -1) {
			repaintToBottom = true;
			repaintRange = new VerticalRange(Math.min(repaintRange.getTop(), childRepaintStart), repaintRange.getBottom());
		}

		layoutState = LAYOUT_OK;

		if (repaintToBottom) {
			repaintRange = new VerticalRange(repaintRange.getTop(), Math.max(originalHeight, getHeight()));
		}

		if (repaintRange == null || repaintRange.isEmpty()) {
			return null;
		}

		return repaintRange;
	}

	protected abstract List<Box> createChildren(LayoutContext context);

	/**
	 * Creates a list of block boxes for a given document range. beforeInlines and afterInlines are prepended/appended
	 * to the first/last block child, and each may be null.
	 */
	protected List<Box> createBlockBoxes(final LayoutContext context, final int startOffset, final int endOffset, final int width, final List<InlineBox> beforeInlines,
			final List<InlineBox> afterInlines) {
		final List<Box> blockBoxes = new ArrayList<Box>();
		final List<InlineBox> pendingInlines = new ArrayList<InlineBox>();

		if (beforeInlines != null) {
			pendingInlines.addAll(beforeInlines);
		}

		final IDocument document = context.getDocument();
		final INode node = document.findCommonNode(startOffset, endOffset);

		if (startOffset == endOffset) {
			final int relOffset = startOffset - node.getStartOffset();
			pendingInlines.add(new PlaceholderBox(context, node, relOffset));
		} else if (node instanceof IParent) {
			final BlockInlineIterator iter = new BlockInlineIterator(context, (IParent) node, startOffset, endOffset);
			while (true) {
				Object next = iter.next();
				if (next == null) {
					break;
				}

				if (next instanceof ContentRange) {
					final ContentRange range = (ContentRange) next;
					final InlineElementBox.InlineBoxes inlineBoxes = InlineElementBox.createInlineBoxes(context, node, range);
					pendingInlines.addAll(inlineBoxes.boxes);
					pendingInlines.add(new PlaceholderBox(context, node, range.getEndOffset() - node.getStartOffset()));
				} else {
					if (!pendingInlines.isEmpty()) {
						blockBoxes.add(ParagraphBox.create(context, node, pendingInlines, width));
						pendingInlines.clear();
					}

					if (isTableChild(context, next)) {
						// Consume continguous table children and create an
						// anonymous table.
						final int tableStartOffset = ((IElement) next).getStartOffset();
						int tableEndOffset = -1; // dummy to hide warning
						while (isTableChild(context, next)) {
							tableEndOffset = ((IElement) next).getEndOffset() + 1;
							next = iter.next();
						}

						// add anonymous table
						blockBoxes.add(new TableBox(context, this, tableStartOffset, tableEndOffset));
						if (next == null) {
							break;
						} else {
							iter.push(next);
						}
					} else { // next is a block box element
						final INode blockNode = (INode) next;
						blockBoxes.add(context.getBoxFactory().createBox(context, blockNode, this, width));
					}
				}
			}
		} else {
			final ContentRange range = new ContentRange(startOffset, endOffset);
			final InlineElementBox.InlineBoxes inlineBoxes = InlineElementBox.createInlineBoxes(context, node, range);
			pendingInlines.addAll(inlineBoxes.boxes);
			pendingInlines.add(new PlaceholderBox(context, node, range.getEndOffset() - node.getStartOffset()));
		}

		if (afterInlines != null) {
			pendingInlines.addAll(afterInlines);
		}

		if (!pendingInlines.isEmpty()) {
			blockBoxes.add(ParagraphBox.create(context, node, pendingInlines, width));
			pendingInlines.clear();
		}

		return blockBoxes;
	}

	private static class BlockInlineIterator {

		private final LayoutContext context;
		private final IParent parent;
		private int startOffset;
		private final int endOffset;
		private final LinkedList<Object> pushStack = new LinkedList<Object>();

		public BlockInlineIterator(final LayoutContext context, final IParent parent, final int startOffset, final int endOffset) {
			this.context = context;
			this.parent = parent;
			this.startOffset = startOffset;
			this.endOffset = endOffset;
		}

		/**
		 * Returns the next block element or inline range, or null if we're at the end.
		 */
		public Object next() {
			if (!pushStack.isEmpty()) {
				return pushStack.removeLast();
			} else if (startOffset >= endOffset) {
				return null;
			} else {
				final INode blockNode = findNextBlockNode(context, parent, startOffset, endOffset);
				if (blockNode == null) {
					if (startOffset < endOffset) {
						final ContentRange result = new ContentRange(startOffset, endOffset);
						startOffset = endOffset;
						return result;
					} else {
						return null;
					}
				} else if (blockNode.getStartOffset() > startOffset) {
					pushStack.addLast(blockNode);
					final ContentRange result = new ContentRange(startOffset, blockNode.getStartOffset());
					startOffset = blockNode.getEndOffset() + 1;
					return result;
				} else {
					startOffset = blockNode.getEndOffset() + 1;
					return blockNode;
				}
			}
		}

		public void push(final Object pushed) {
			pushStack.addLast(pushed);
		}

	}

	protected boolean hasChildren() {
		return getChildren() != null && getChildren().length > 0;
	}

	/**
	 * Positions the children of this box. Vertical margins are collapsed here. Returns the vertical offset of the top
	 * of the first child to move, or -1 if not children were actually moved.
	 */
	protected int positionChildren(final LayoutContext context) {

		int childY = 0;
		int prevMargin = 0;
		final BlockBox[] children = getBlockChildren();
		int repaintStart = -1;

		Styles styles = null;

		if (!isAnonymous()) {
			styles = context.getStyleSheet().getStyles(getNode());
		}

		if (styles != null && children.length > 0) {
			if (styles.getBorderTopWidth() + styles.getPaddingTop().get(getWidth()) == 0) {
				// first child's top margin collapses into ours
				marginTop = Math.max(marginTop, children[0].getMarginTop());
				childY -= children[0].getMarginTop();
			}
		}

		for (int i = 0; i < children.length; i++) {

			final Insets insets = children[i].getInsets(context, getWidth());

			childY += insets.getTop();

			if (i > 0) {
				childY -= Math.min(prevMargin, children[i].getMarginTop());
			}

			if (repaintStart == -1 && children[i].getY() != childY) {
				repaintStart = Math.min(children[i].getY(), childY);
			}

			children[i].setX(insets.getLeft());
			children[i].setY(childY);

			childY += children[i].getHeight() + insets.getBottom();
			prevMargin = children[i].getMarginBottom();
		}

		if (styles != null && children.length > 0) {
			if (styles.getBorderBottomWidth() + styles.getPaddingBottom().get(getWidth()) == 0) {
				// last child's bottom margin collapses into ours
				marginBottom = Math.max(marginBottom, prevMargin);
				childY -= prevMargin;
			}
		}

		setHeight(childY);

		return repaintStart;
	}

	/**
	 * Sets the layout state of the box.
	 * 
	 * @param layoutState
	 *            One of the LAYOUT_* constants
	 */
	protected void setLayoutState(final byte layoutState) {
		this.layoutState = layoutState;
	}

	// ========================================================= PRIVATE

	/**
	 * Searches for the next block-formatted child.
	 * 
	 * @param context
	 *            LayoutContext to use.
	 * @param parent
	 *            Element within which to search.
	 * @param startOffset
	 *            The offset at which to start the search.
	 * @param endOffset
	 *            The offset at which to end the search.
	 */
	private static INode findNextBlockNode(final LayoutContext context, final IParent parent, final int startOffset, final int endOffset) {
		for (final INode child : parent.children().in(new ContentRange(startOffset, endOffset)).withoutText()) {
			final INode nextBlockNode = child.accept(new BaseNodeVisitorWithResult<INode>() {
				@Override
				public INode visit(final IElement element) {
					// found?
					if (!isInline(context, element, parent)) {
						return element;
					}

					// recursion
					final INode fromChild = findNextBlockNode(context, element, startOffset, endOffset);
					if (fromChild != null) {
						return fromChild;
					}

					return null;
				}

				@Override
				public INode visit(final IComment comment) {
					if (!isInline(context, comment, parent)) {
						return comment;
					}
					return null;
				}

				@Override
				public INode visit(final IProcessingInstruction pi) {
					if (!isInline(context, pi, parent)) {
						return pi;
					}
					return null;
				}
			});
			if (nextBlockNode != null) {
				return nextBlockNode;
			}
		}

		return null;
	}

	private static boolean isInline(final LayoutContext context, final INode child, final INode parent) {
		final String style = displayStyleOf(child, context);
		final String parentStyle = displayStyleOf(parent, context);

		return child.accept(new BaseNodeVisitorWithResult<Boolean>(false) {
			@Override
			public Boolean visit(final IElement element) {
				if (style.equals(CSS.INLINE)) {
					return true;
				}

				// invalid nested table elements have to be shown as 'inline': 

				// parent of 'table-cell': 'table-row'
				if (style.equals(CSS.TABLE_CELL) && !parentStyle.equals(CSS.TABLE_ROW)) {
					return true;
				}

				// parent of 'table-row': 'table', 'table-row-group', 
				// 'table-header-group' or 'table-footer-group'
				if (style.equals(CSS.TABLE_ROW) && !parentStyle.equals(CSS.TABLE) && !parentStyle.equals(CSS.TABLE_ROW_GROUP) && !parentStyle.equals(CSS.TABLE_HEADER_GROUP)
						&& !parentStyle.equals(CSS.TABLE_FOOTER_GROUP)) {
					return true;
				}

				// parent of 'table-row-group', table-header-group',
				// or 'table-footer-group': 'table', 'table-row-group'
				if ((style.equals(CSS.TABLE_ROW_GROUP) || style.equals(CSS.TABLE_HEADER_GROUP) || style.equals(CSS.TABLE_FOOTER_GROUP))
						&& !(parentStyle.equals(CSS.TABLE) || parentStyle.equals(CSS.TABLE_ROW_GROUP))) {
					return true;
				}

				// parent of 'table-column': 'table-column-group'
				if (style.equals(CSS.TABLE_COLUMN) && !parentStyle.equals(CSS.TABLE_COLUMN_GROUP)) {
					return true;
				}

				// parent of 'table-column-group': 'table'
				if (style.equals(CSS.TABLE_COLUMN_GROUP) && !parentStyle.equals(CSS.TABLE)) {
					return true;
				}

				// parent of 'table-caption': 'table'
				if (style.equals(CSS.TABLE_CAPTION) && !parentStyle.equals(CSS.TABLE)) {
					return true;
				}

				return false;
			}

			@Override
			public Boolean visit(final IComment comment) {
				final boolean parentIsInline = parent.accept(new BaseNodeVisitorWithResult<Boolean>(false) {
					@Override
					public Boolean visit(final IElement element) {
						return parentStyle.equals(CSS.INLINE);
					};
				});
				return parentIsInline && style.equals(CSS.INLINE);
			}

			@Override
			public Boolean visit(final IProcessingInstruction pi) {
				final boolean parentIsInline = parent.accept(new BaseNodeVisitorWithResult<Boolean>(false) {
					@Override
					public Boolean visit(final IElement element) {
						return parentStyle.equals(CSS.INLINE);
					};
				});
				return parentIsInline && style.equals(CSS.INLINE);
			}

			@Override
			public Boolean visit(final IText text) {
				return true;
			}
		});
	}

	private static String displayStyleOf(final INode node, final LayoutContext context) {
		return context.getStyleSheet().getStyles(node).getDisplay();
	}

	/**
	 * Return the end position of an anonymous box. The default implementation returns null.
	 */
	private IPosition getEndPosition() {
		return endPosition;
	}

	/**
	 * Return the start position of an anonymous box. The default implementation returns null.
	 */
	private IPosition getStartPosition() {
		return startPosition;
	}

	private boolean isTableChild(final LayoutContext context, final Object rangeOrElement) {
		if (rangeOrElement != null && rangeOrElement instanceof IElement) {
			return LayoutUtils.isTableChild(context.getStyleSheet(), (IElement) rangeOrElement);
		} else {
			return false;
		}
	}

}
