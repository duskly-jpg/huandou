package com.huandou.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.PorterDuff;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Lightweight pull-to-refresh container, no androidx dependency. */
public class PullRefreshLayout extends FrameLayout {

    public interface OnRefreshListener {
        void onRefresh();
    }

    /** Lets the host WebView report whether the page itself can still scroll up. */
    public interface ScrollCheck {
        boolean canScrollUp();
    }

    private final View mHeader;
    private final TextView mLabel;
    private final ProgressBar mSpinner;
    private final int mHeaderH;
    private final float mTrigger;
    private final float mMax;

    private View mTarget;
    private OnRefreshListener mListener;
    private ScrollCheck mScrollCheck;
    private float mDownY;
    private float mOffset;
    private boolean mDragging;
    private boolean mRefreshing;
    private int mActivePointer = -1;
    private ValueAnimator mAnimator;

    public PullRefreshLayout(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        mHeaderH = (int) (64 * d);
        mTrigger = 96 * d;
        mMax = 200 * d;
        setBackgroundColor(0xFF0A070D);

        LinearLayout h = new LinearLayout(c);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER);
        h.setBackgroundColor(0xFF171021);
        h.setTranslationY(-mHeaderH);

        mSpinner = new ProgressBar(c, null, android.R.attr.progressBarStyleSmall);
        mSpinner.setVisibility(GONE);
        try {
            if (mSpinner.getIndeterminateDrawable() != null) {
                mSpinner.getIndeterminateDrawable().setColorFilter(0xFF9B7BFF, PorterDuff.Mode.SRC_IN);
            }
        } catch (Throwable ignored) {
        }
        LinearLayout.LayoutParams spinLp = new LinearLayout.LayoutParams((int) (20 * d), (int) (20 * d));
        spinLp.rightMargin = (int) (10 * d);
        h.addView(mSpinner, spinLp);

        mLabel = new TextView(c);
        mLabel.setText("下拉刷新");
        mLabel.setTextColor(0xFFC8BCDA);
        mLabel.setTextSize(13);
        h.addView(mLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addView(h, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, mHeaderH, Gravity.TOP));
        mHeader = h;
    }

    public void setOnRefreshListener(OnRefreshListener l) {
        mListener = l;
    }

    public void setScrollCheck(ScrollCheck c) {
        mScrollCheck = c;
    }

    public void setTarget(View v) {
        mTarget = v;
        mHeader.bringToFront();
    }

    public void finishRefresh() {
        if (!mRefreshing && mOffset <= 0.5f) return;
        mRefreshing = false;
        updateLabel();
        settle(0f, null);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mRefreshing || mListener == null || mTarget == null) return false;
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (mAnimator != null) mAnimator.cancel();
            mActivePointer = ev.getPointerId(0);
            mDownY = ev.getY();
            mDragging = false;
            return false;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            int idx = ev.findPointerIndex(mActivePointer);
            if (idx < 0) return false;
            float dy = ev.getY(idx) - mDownY;
            if (dy > 24f && !canChildScrollUp()) {
                mDragging = true;
                return true;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mActivePointer = -1;
            mDragging = false;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!mDragging && !mRefreshing) return super.onTouchEvent(ev);
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE) {
            int idx = ev.findPointerIndex(mActivePointer);
            if (idx < 0) return true;
            mOffset = damp(ev.getY(idx) - mDownY);
            applyOffset();
            updateLabel();
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mDragging = false;
            mActivePointer = -1;
            if (mOffset >= mTrigger && !mRefreshing) {
                beginRefresh();
            } else if (!mRefreshing) {
                settle(0f, null);
            }
            return true;
        }
        return true;
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        if (disallowIntercept) mDragging = false;
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
    }

    private boolean canChildScrollUp() {
        if (mTarget == null) return false;
        if (mScrollCheck != null && mScrollCheck.canScrollUp()) return true;
        return mTarget.canScrollVertically(-1) || mTarget.getScrollY() > 0;
    }

    private float damp(float dy) {
        if (dy <= 0f) return 0f;
        return (float) (mMax * (1.0 - Math.exp(-dy / (mMax * 1.15))));
    }

    private void applyOffset() {
        if (mTarget != null) mTarget.setTranslationY(mOffset);
        mHeader.setTranslationY(mOffset - mHeaderH);
    }

    private void updateLabel() {
        if (mRefreshing) {
            mLabel.setText("正在刷新…");
            mSpinner.setVisibility(VISIBLE);
        } else if (mOffset >= mTrigger) {
            mLabel.setText("松开即可刷新");
            mSpinner.setVisibility(GONE);
        } else {
            mLabel.setText("下拉刷新");
            mSpinner.setVisibility(GONE);
        }
    }

    private void beginRefresh() {
        mRefreshing = true;
        updateLabel();
        settle(mTrigger, new Runnable() {
            @Override
            public void run() {
                if (mListener != null) mListener.onRefresh();
            }
        });
    }

    private void settle(float to, final Runnable after) {
        if (mAnimator != null) mAnimator.cancel();
        mAnimator = ValueAnimator.ofFloat(mOffset, to);
        mAnimator.setDuration(240);
        mAnimator.setInterpolator(new DecelerateInterpolator());
        mAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                mOffset = ((Float) a.getAnimatedValue()).floatValue();
                applyOffset();
            }
        });
        if (after != null) {
            mAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator a) {
                    after.run();
                }
            });
        }
        mAnimator.start();
    }
}
