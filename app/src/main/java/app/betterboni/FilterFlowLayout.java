package app.betterboni;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Compact filter tiles that wrap to the available dialog width. */
final class FilterFlowLayout extends ViewGroup {
    private final int gap;

    FilterFlowLayout(Context context) {
        super(context);
        gap = Math.round(6 * getResources().getDisplayMetrics().density);
    }

    @Override protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int available = Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
        int rowWidth = 0, rowHeight = 0, width = 0, height = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            measureChild(child, widthSpec, heightSpec);
            int space = rowWidth == 0 ? 0 : gap;
            if (rowWidth > 0 && rowWidth + space + child.getMeasuredWidth() > available) {
                width = Math.max(width, rowWidth);
                height += rowHeight + gap;
                rowWidth = 0;
                rowHeight = 0;
                space = 0;
            }
            rowWidth += space + child.getMeasuredWidth();
            rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
        }
        width = Math.max(width, rowWidth);
        height += rowHeight;
        setMeasuredDimension(resolveSize(width + getPaddingLeft() + getPaddingRight(), widthSpec),
                resolveSize(height + getPaddingTop() + getPaddingBottom(), heightSpec));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int x = getPaddingLeft(), y = getPaddingTop(), rowHeight = 0;
        int edge = right - left - getPaddingRight();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int width = child.getMeasuredWidth(), height = child.getMeasuredHeight();
            if (x > getPaddingLeft() && x + width > edge) {
                x = getPaddingLeft();
                y += rowHeight + gap;
                rowHeight = 0;
            }
            child.layout(x, y, x + width, y + height);
            x += width + gap;
            rowHeight = Math.max(rowHeight, height);
        }
    }
}
