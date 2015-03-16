package com.jph.view;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.AbsListView;
import android.widget.AbsListView.OnScrollListener;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.Scroller;
import android.widget.TextView;

import com.jph.lp.R;

/**
 * 基于ListView的自定义控件实现上拉加载下拉刷新
 * 
 * @author JPH
 * @date 2015-3-10 下午1:01:56
 */
public class ListViewPlus extends ListView implements OnScrollListener {
	/** 区分当前操作是刷新还是加载 **/
	public static final int REFRESH = 0;
	public static final int LOAD = 1;

	private final static int SCROLL_BACK_HEADER = 0;
	private final static int SCROLL_BACK_FOOTER = 1;

	private final static int SCROLL_DURATION = 400;

	// when pull up >= 50px
	private final static int PULL_LOAD_MORE_DELTA = 50;

	// support iOS like pull
	private final static float OFFSET_RADIO = 1.8f;

	private float mLastY = -1;
	private int minItemCount = 3;
	// used for scroll back
	private Scroller mScroller;
	// user's scroll listener
	private OnScrollListener mScrollListener;
	// for mScroller, scroll back from header or footer.
	private int mScrollBack;

	// the interface to trigger refresh and load more.
	private ListViewPlusListener mListener;

	private ListViewPlusHeader mHeader;
	// header view content, use it to calculate the Header's height. And hide it
	// when disable pull refresh.
	private RelativeLayout mHeaderContent;
	private TextView mHeaderTime;
	private int mHeaderHeight;

	private LinearLayout mFooterLayout;
	private ListViewPlusFooter mFooterView;
	private boolean mIsFooterReady = false;

	private boolean mEnablePullRefresh = true;
	private boolean mPullRefreshing = false;

	private boolean mEnablePullLoad = true;
	private boolean mEnableAutoLoad = false;
	private boolean mPullLoading = false;

	// total list items, used to detect is at the bottom of ListView
	private int mTotalItemCount;

	public ListViewPlus(Context context) {
		super(context);
		initWithContext(context);
	}

	public ListViewPlus(Context context, AttributeSet attrs) {
		super(context, attrs);
		initWithContext(context);
	}

	public ListViewPlus(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		initWithContext(context);
	}

	private void initWithContext(Context context) {
		mScroller = new Scroller(context, new DecelerateInterpolator());
		super.setOnScrollListener(this);

		// init header view
		mHeader = new ListViewPlusHeader(context);
		mHeaderContent = (RelativeLayout) mHeader
				.findViewById(R.id.listview_plus_header_content);
		mHeaderTime = (TextView) mHeader
				.findViewById(R.id.listview_plus_header_time);
		addHeaderView(mHeader);

		// init footer view
		mFooterView = new ListViewPlusFooter(context);
		mFooterLayout = new LinearLayout(context);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.MATCH_PARENT);
		params.gravity = Gravity.CENTER;
		mFooterLayout.addView(mFooterView, params);

		// init header height
		ViewTreeObserver observer = mHeader.getViewTreeObserver();
		if (null != observer) {
			observer.addOnGlobalLayoutListener(new OnGlobalLayoutListener() {
				@SuppressWarnings("deprecation")
				@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
				@Override
				public void onGlobalLayout() {
					mHeaderHeight = mHeaderContent.getHeight();
					ViewTreeObserver observer = getViewTreeObserver();

					if (null != observer) {
						if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
							observer.removeGlobalOnLayoutListener(this);
						} else {
							observer.removeOnGlobalLayoutListener(this);
						}
					}
				}
			});
		}
	}

	@Override
	public void setAdapter(ListAdapter adapter) {
		// make sure ListViewPlusFooter is the last footer view, and only add
		// once.
		if (!mIsFooterReady) {
			mIsFooterReady = true;
			addFooterView(mFooterLayout);
		}

		super.setAdapter(adapter);
	}

	/**
	 * Enable or disable pull down refresh feature.
	 * 
	 * @param enable
	 */
	public void setRefreshEnable(boolean enable) {
		mEnablePullRefresh = enable;

		// disable, hide the content
		mHeaderContent.setVisibility(enable ? View.VISIBLE : View.INVISIBLE);
	}

	/**
	 * Enable or disable pull up load more feature.
	 * 
	 * @param enable
	 */
	public void setLoadEnable(boolean enable) {
		mEnablePullLoad = enable;

		if (!mEnablePullLoad) {
			mFooterView.setBottomMargin(0);
			mFooterView.hide();
			mFooterView.setPadding(0, 0, 0, mFooterView.getHeight() * (-1));
			mFooterView.setOnClickListener(null);

		} else {
			mPullLoading = false;
			mFooterView.setPadding(0, 0, 0, 0);
			mFooterView.show();
			mFooterView.setState(ListViewPlusFooter.STATE_NORMAL);
			// both "pull up" and "click" will invoke load more.
			mFooterView.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View v) {
					startLoadMore();
				}
			});
		}
	}

	/**
	 * Enable or disable auto load more feature when scroll to bottom.
	 * 
	 * @param enable
	 */
	public void setAutoLoadEnable(boolean enable) {
		mEnableAutoLoad = enable;
	}

	/**
	 * Stop refresh, reset header view.
	 */
	public void stopRefresh() {
		if (mPullRefreshing) {
			mPullRefreshing = false;
			resetHeaderHeight();
		}
	}

	/**
	 * Stop load more, reset footer view.
	 */
	public void stopLoadMore() {
		if (mPullLoading) {
			mPullLoading = false;
			mFooterView.setState(ListViewPlusFooter.STATE_NORMAL);
		}
	}

	/**
	 * Set last refresh time
	 * 
	 * @param time
	 */
	public void setRefreshTime(String time) {
		mHeaderTime.setText(time);
	}

	/**
	 * Set listener.
	 * 
	 * @param listener
	 */
	public void setListViewPlusListener(ListViewPlusListener listener) {
		mListener = listener;
	}

	/**
	 * Auto call back refresh.
	 */
	public void autoRefresh() {
		mHeader.setVisibleHeight(mHeaderHeight);

		if (mEnablePullRefresh && !mPullRefreshing) {
			// update the arrow image not refreshing
			if (mHeader.getVisibleHeight() > mHeaderHeight) {
				mHeader.setState(ListViewPlusHeader.STATE_READY);
			} else {
				mHeader.setState(ListViewPlusHeader.STATE_NORMAL);
			}
		}

		mPullRefreshing = true;
		mHeader.setState(ListViewPlusHeader.STATE_REFRESHING);
		refresh();
	}

	private void invokeOnScrolling() {
		if (mScrollListener instanceof OnXScrollListener) {
			OnXScrollListener listener = (OnXScrollListener) mScrollListener;
			listener.onXScrolling(this);
		}
	}

	private void updateHeaderHeight(float delta) {
		mHeader.setVisibleHeight((int) delta + mHeader.getVisibleHeight());

		if (mEnablePullRefresh && !mPullRefreshing) {
			// update the arrow image unrefreshing
			if (mHeader.getVisibleHeight() > mHeaderHeight) {
				mHeader.setState(ListViewPlusHeader.STATE_READY);
			} else {
				mHeader.setState(ListViewPlusHeader.STATE_NORMAL);
			}
		}

		// scroll to top each time
		setSelection(0);
	}

	private void resetHeaderHeight() {
		int height = mHeader.getVisibleHeight();
		if (height == 0)
			return;

		// refreshing and header isn't shown fully. do nothing.
		if (mPullRefreshing && height <= mHeaderHeight)
			return;

		// default: scroll back to dismiss header.
		int finalHeight = 0;
		// is refreshing, just scroll back to show all the header.
		if (mPullRefreshing && height > mHeaderHeight) {
			finalHeight = mHeaderHeight;
		}

		mScrollBack = SCROLL_BACK_HEADER;
		mScroller.startScroll(0, height, 0, finalHeight - height,
				SCROLL_DURATION);

		// trigger computeScroll
		invalidate();
	}

	private void updateFooterHeight(float delta) {
		int height = mFooterView.getBottomMargin() + (int) delta;

		if (mEnablePullLoad && !mPullLoading) {
			if (height > PULL_LOAD_MORE_DELTA) {
				// height enough to invoke load more.
				mFooterView.setState(ListViewPlusFooter.STATE_READY);
			} else {
				mFooterView.setState(ListViewPlusFooter.STATE_NORMAL);
			}
		}

		mFooterView.setBottomMargin(height);

		// scroll to bottom
		// setSelection(mTotalItemCount - 1);
	}

	private void resetFooterHeight() {
		int bottomMargin = mFooterView.getBottomMargin();

		if (bottomMargin > 0) {
			mScrollBack = SCROLL_BACK_FOOTER;
			mScroller.startScroll(0, bottomMargin, 0, -bottomMargin,
					SCROLL_DURATION);
			invalidate();
		}
	}

	private void startLoadMore() {
		mPullLoading = true;
		mFooterView.setState(ListViewPlusFooter.STATE_LOADING);
		loadMore();
	}

	@Override
	public boolean onTouchEvent(MotionEvent ev) {
		if (mLastY == -1) {
			mLastY = ev.getRawY();
		}

		switch (ev.getAction()) {
		case MotionEvent.ACTION_DOWN:
			mLastY = ev.getRawY();
			break;

		case MotionEvent.ACTION_MOVE:
			final float deltaY = ev.getRawY() - mLastY;
			mLastY = ev.getRawY();

			if (getFirstVisiblePosition() == 0
					&& (mHeader.getVisibleHeight() > 0 || deltaY > 0)) {
				// the first item is showing, header has shown or pull down.
				updateHeaderHeight(deltaY / OFFSET_RADIO);
				invokeOnScrolling();

			} else if (getLastVisiblePosition() == mTotalItemCount - 1
					&& (mFooterView.getBottomMargin() > 0 || deltaY < 0)) {
				// last item, already pulled up or want to pull up.
				updateFooterHeight(-deltaY / OFFSET_RADIO);
			}
			break;

		default:
			// reset
			mLastY = -1;
			if (getFirstVisiblePosition() == 0) {
				// invoke refresh
				if (mEnablePullRefresh
						&& mHeader.getVisibleHeight() > mHeaderHeight) {
					mPullRefreshing = true;
					mHeader.setState(ListViewPlusHeader.STATE_REFRESHING);
					refresh();
				}

				resetHeaderHeight();

			} else if (getLastVisiblePosition() == mTotalItemCount - 1) {
				// invoke load more.
				if (mEnablePullLoad
						&& mFooterView.getBottomMargin() > PULL_LOAD_MORE_DELTA) {
					startLoadMore();
				}
				resetFooterHeight();
			}
			break;
		}
		return super.onTouchEvent(ev);
	}

	@Override
	public void computeScroll() {
		if (mScroller.computeScrollOffset()) {
			if (mScrollBack == SCROLL_BACK_HEADER) {
				mHeader.setVisibleHeight(mScroller.getCurrY());
			} else {
				mFooterView.setBottomMargin(mScroller.getCurrY());
			}

			postInvalidate();
			invokeOnScrolling();
		}

		super.computeScroll();
	}

	@Override
	public void setOnScrollListener(OnScrollListener l) {
		mScrollListener = l;
	}

	@Override
	public void onScrollStateChanged(AbsListView view, int scrollState) {
		if (mScrollListener != null) {
			mScrollListener.onScrollStateChanged(view, scrollState);
		}

		if (scrollState == OnScrollListener.SCROLL_STATE_IDLE) {
			if (mEnableAutoLoad && getLastVisiblePosition() == getCount() - 1) {
				startLoadMore();
			}
		}
	}

	@Override
	public void onScroll(AbsListView view, int firstVisibleItem,
			int visibleItemCount, int totalItemCount) {
		// send to user's listener
		mTotalItemCount = totalItemCount;
		if (mScrollListener != null) {
			mScrollListener.onScroll(view, firstVisibleItem, visibleItemCount,
					totalItemCount);
		}
		if (mFooterView != null) {
			if (totalItemCount < minItemCount + 2) {// 如果ListView中只包含头布局和尾布局（及ListView的adapter没有数据），则不显示脚布局文件
				this.removeFooterView(mFooterLayout);
			} else {
				if(this.getFooterViewsCount()<1)this.addFooterView(mFooterLayout);
			}
		}
	}
	private void refresh() {
		if (mEnablePullRefresh && null != mListener) {
			mListener.onRefresh();
		}
	}

	private void loadMore() {
		if (mEnablePullLoad && null != mListener) {
			mListener.onLoadMore();
		}
	}

	/**
	 * You can listen ListView.OnScrollListener or this one. it will invoke
	 * onXScrolling when header/footer scroll back.
	 */
	public interface OnXScrollListener extends OnScrollListener {
		public void onXScrolling(View view);
	}

	/**
	 * 实现这个接口可以，获取下拉加载上拉刷新事件
	 */
	public interface ListViewPlusListener {
		public void onRefresh();

		public void onLoadMore();
	}

	public String getCurrentTime() {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd  HH:mm:ss",
				Locale.getDefault());
		String currentTime = sdf.format(new Date());
		return currentTime;
	}

	/**
	 * ListView的Footer类
	 * 
	 * @author JPH
	 */
	class ListViewPlusFooter extends LinearLayout {
		public final static int STATE_NORMAL = 0;
		public final static int STATE_READY = 1;
		public final static int STATE_LOADING = 2;

		private final int ROTATE_ANIM_DURATION = 180;

		private View mLayout;

		private View mProgressBar;

		private TextView mHintView;

		// private ImageView mHintImage;

		private Animation mRotateUpAnim;
		private Animation mRotateDownAnim;

		private int mState = STATE_NORMAL;

		public ListViewPlusFooter(Context context) {
			super(context);
			initView(context);
		}

		public ListViewPlusFooter(Context context, AttributeSet attrs) {
			super(context, attrs);
			initView(context);
		}

		private void initView(Context context) {
			mLayout = LayoutInflater.from(context).inflate(
					R.layout.listview_plus_footer, null);
			mLayout.setLayoutParams(new LinearLayout.LayoutParams(
					LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
			addView(mLayout);

			mProgressBar = mLayout
					.findViewById(R.id.listview_plus_footer_progressbar);
			mHintView = (TextView) mLayout
					.findViewById(R.id.listview_plus_footer_hint_textview);
			// mHintImage = (ImageView) mLayout.findViewById(R.id.footer_arrow);

			mRotateUpAnim = new RotateAnimation(0.0f, 180.0f,
					Animation.RELATIVE_TO_SELF, 0.5f,
					Animation.RELATIVE_TO_SELF, 0.5f);
			mRotateUpAnim.setDuration(ROTATE_ANIM_DURATION);
			mRotateUpAnim.setFillAfter(true);

			mRotateDownAnim = new RotateAnimation(180.0f, 0.0f,
					Animation.RELATIVE_TO_SELF, 0.5f,
					Animation.RELATIVE_TO_SELF, 0.5f);
			mRotateDownAnim.setDuration(ROTATE_ANIM_DURATION);
			mRotateDownAnim.setFillAfter(true);
		}

		public TextView getmHintView() {
			return mHintView;
		}

		/**
		 * Set footer view state
		 * 
		 * @see #STATE_LOADING
		 * @see #STATE_NORMAL
		 * @see #STATE_READY
		 * 
		 * @param state
		 */
		public void setState(int state) {
			if (state == mState)
				return;

			if (state == STATE_LOADING) {
				// mHintImage.clearAnimation();
				// mHintImage.setVisibility(View.INVISIBLE);
				mProgressBar.setVisibility(View.VISIBLE);
				mHintView.setVisibility(View.INVISIBLE);
			} else {
				mHintView.setVisibility(View.VISIBLE);
				// mHintImage.setVisibility(View.VISIBLE);
				mProgressBar.setVisibility(View.INVISIBLE);
			}

			switch (state) {
			case STATE_NORMAL:
				// if (mState == STATE_READY) {
				// mHintImage.startAnimation(mRotateDownAnim);
				// }
				// if (mState == STATE_LOADING) {
				// mHintImage.clearAnimation();
				// }
				mHintView.setText(R.string.listview_plus_footer_hint_normal);
				break;

			case STATE_READY:
				if (mState != STATE_READY) {
					// mHintImage.clearAnimation();
					// mHintImage.startAnimation(mRotateUpAnim);
					mHintView.setText(R.string.listview_plus_footer_hint_ready);
				}
				break;

			case STATE_LOADING:
				break;
			}

			mState = state;
		}

		/**
		 * Set footer view bottom margin.
		 * 
		 * @param margin
		 */
		public void setBottomMargin(int margin) {
			if (margin < 0)
				return;
			LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mLayout
					.getLayoutParams();
			lp.bottomMargin = margin;
			mLayout.setLayoutParams(lp);
		}

		/**
		 * Get footer view bottom margin.
		 * 
		 * @return
		 */
		public int getBottomMargin() {
			LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mLayout
					.getLayoutParams();
			return lp.bottomMargin;
		}

		/**
		 * normal status
		 */
		public void normal() {
			mHintView.setVisibility(View.VISIBLE);
			mProgressBar.setVisibility(View.GONE);
		}

		/**
		 * loading status
		 */
		public void loading() {
			mHintView.setVisibility(View.GONE);
			mProgressBar.setVisibility(View.VISIBLE);
		}

		/**
		 * hide footer when disable pull load more
		 */
		public void hide() {
			LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mLayout
					.getLayoutParams();
			lp.height = 0;
			mLayout.setLayoutParams(lp);
		}

		/**
		 * show footer
		 */
		public void show() {
			LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mLayout
					.getLayoutParams();
			lp.height = LayoutParams.WRAP_CONTENT;
			mLayout.setLayoutParams(lp);
		}
	}

	/**
	 * listView的头布局
	 * 
	 * @author JPH
	 */
	class ListViewPlusHeader extends LinearLayout {
		private LinearLayout mContainer;
		private ImageView mArrowImageView;
		private ProgressBar mProgressBar;
		private TextView mHintTextView;
		private int mState = STATE_NORMAL;
		private Animation mRotateUpAnim;
		private Animation mRotateDownAnim;
		private final int ROTATE_ANIM_DURATION = 180;
		public final static int STATE_NORMAL = 0;
		public final static int STATE_READY = 1;
		public final static int STATE_REFRESHING = 2;

		public ListViewPlusHeader(Context context) {
			super(context);
			initView(context);
		}

		/**
		 * @param context
		 * @param attrs
		 */
		public ListViewPlusHeader(Context context, AttributeSet attrs) {
			super(context, attrs);
			initView(context);
		}

		private void initView(Context context) {
			// 初始情况，设置下拉刷新view高度为0
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
					LayoutParams.MATCH_PARENT, 0);
			mContainer = (LinearLayout) LayoutInflater.from(context).inflate(
					R.layout.listview_plus_header, null);
			addView(mContainer, lp);
			setGravity(Gravity.BOTTOM);

			mArrowImageView = (ImageView) findViewById(R.id.listview_plus_header_arrow);
			mHintTextView = (TextView) findViewById(R.id.listview_plus_header_hint_textview);
			mProgressBar = (ProgressBar) findViewById(R.id.listview_plus_header_progressbar);

			mRotateUpAnim = new RotateAnimation(0.0f, -180.0f,
					Animation.RELATIVE_TO_SELF, 0.5f,
					Animation.RELATIVE_TO_SELF, 0.5f);
			mRotateUpAnim.setDuration(ROTATE_ANIM_DURATION);
			mRotateUpAnim.setFillAfter(true);
			mRotateDownAnim = new RotateAnimation(-180.0f, 0.0f,
					Animation.RELATIVE_TO_SELF, 0.5f,
					Animation.RELATIVE_TO_SELF, 0.5f);
			mRotateDownAnim.setDuration(ROTATE_ANIM_DURATION);
			mRotateDownAnim.setFillAfter(true);
		}

		public void setState(int state) {
			if (state == mState)
				return;

			if (state == STATE_REFRESHING) { // 显示进度
				mArrowImageView.clearAnimation();
				mArrowImageView.setVisibility(View.INVISIBLE);
				mProgressBar.setVisibility(View.VISIBLE);
			} else { // 显示箭头图片
				mArrowImageView.setVisibility(View.VISIBLE);
				mProgressBar.setVisibility(View.INVISIBLE);
			}

			switch (state) {
			case STATE_NORMAL:
				if (mState == STATE_READY) {
					mArrowImageView.startAnimation(mRotateDownAnim);
				}
				if (mState == STATE_REFRESHING) {
					mArrowImageView.clearAnimation();
				}
				mHintTextView
						.setText(R.string.listview_plus_header_hint_normal);
				break;
			case STATE_READY:
				if (mState != STATE_READY) {
					mArrowImageView.clearAnimation();
					mArrowImageView.startAnimation(mRotateUpAnim);
					mHintTextView
							.setText(R.string.listview_plus_header_hint_ready);
				}
				break;
			case STATE_REFRESHING:
				mHintTextView
						.setText(R.string.listview_plus_header_hint_loading);
				break;
			default:
			}

			mState = state;
		}

		public void setVisibleHeight(int height) {
			if (height < 0)
				height = 0;
			LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mContainer
					.getLayoutParams();
			lp.height = height;
			mContainer.setLayoutParams(lp);
		}

		public int getVisibleHeight() {
			return mContainer.getLayoutParams().height;
		}
	}
}