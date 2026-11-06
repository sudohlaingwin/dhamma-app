package com.h2h.dhamma;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * Photos are already circularised by ImageLoader, so this is a plain
 * ImageView marker class that lets layouts name the round portrait view.
 */
public class CircleImageView extends ImageView {
    public CircleImageView(Context c) { super(c); }
    public CircleImageView(Context c, AttributeSet a) { super(c, a); }
    public CircleImageView(Context c, AttributeSet a, int s) { super(c, a, s); }
}
