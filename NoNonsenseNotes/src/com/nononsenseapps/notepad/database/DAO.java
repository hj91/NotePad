package com.nononsenseapps.notepad.database;

import java.util.ArrayList;
import java.util.List;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.BaseColumns;
import android.util.Log;

public abstract class DAO {

	public static final String whereIdIs = "" + BaseColumns._ID + " IS ?";

	public String[] whereIdArg() {
		return new String[] { Long.toString(_id) };
	}
	
	public static String[] prefixArray(final String prefix, final String[] array) {
		final String[] result = new String[array.length];
		for (int i = 0; i < array.length; i++) {
			result[i] = "" + prefix + array[i];
		}
		return result;
	}
	
	public static String[] joinArrays(final String[]... arrays) {
		final ArrayList<String> list = new ArrayList<String>();
		for (final String[] array: arrays) {
			for (final String txt: array) {
				list.add(txt);
			}
		}
		return list.toArray(new String[list.size()]);
	}
	
	/**
	 * Example: [] -> ""
	 * [a] -> "a"
	 * [a, b] -> "a,b"
	 */
	public static String arrayToCommaString(final String[] array) {
		return arrayToCommaString("", array);
	}
	
	/**
	 * Example (prefix=t.): 
	 * [] -> ""
	 * [a] -> "t.a"
	 * [a, b] -> "t.a,t.b"
	 */
	public static String arrayToCommaString(final String prefix, final String[] array) {
		return arrayToCommaString(prefix, array, "");
	}
	
	/**
	 * Example (prefix=t., suffix=.45): 
	 * [] -> ""
	 * [a] -> "t.a.45"
	 * [a, b] -> "t.a.45,t.b.45"
	 * 
	 * In addition, the txt itself can be referenced using %1$s in either
	 * prefix or suffix. The prefix can be referenced as %2$s in suffix, and vice-versa.
	 * 
	 * So the following is valid:
	 * 
	 * (prefix='t.', suffix=' AS %2$s%1$s')
	 * 
	 * [listId] -> t.listId AS t.listId
	 */
	protected static String arrayToCommaString(final String pfx, 
			final String[] array, final String sfx) {
		StringBuilder result = new StringBuilder();
		for (final String txt: array) {
			if (result.length() > 0)
				result.append(",");
			result.append(String.format(pfx, txt, sfx));
			result.append(txt);
			result.append(String.format(sfx, txt, pfx));
		}
		return result.toString();
	}

	public Uri getUri() {
		return Uri.withAppendedPath(getBaseUri(), Long.toString(_id));
	}

	public Uri getBaseUri() {
		return Uri.withAppendedPath(
				Uri.parse(MyContentProvider.SCHEME
						+ MyContentProvider.AUTHORITY), getTableName());
	}

	public long _id = -1;

	public synchronized boolean update(final Context context,
			final SQLiteDatabase db) {
		int result = 0;
		db.beginTransaction();

		try {

			if (_id > 0) {
				result += db.update(getTableName(), getContent(),
						whereIdIs,
						whereIdArg());
			}

			if (result > 0) {
				db.setTransactionSuccessful();
			}

		} catch (SQLException e) {
			throw e;
		} finally {
			db.endTransaction();
		}

		if (result > 0) {
			notifyProviderOnChange(context);
		}

		return result > 0;
	}

	public synchronized Uri insert(final Context context,
			final SQLiteDatabase db) {
		Uri retval = null;
		db.beginTransaction();
		try {
			beforeInsert(context, db);

			final long id = db.insert(getTableName(), null, getContent());

			if (id == -1) {
				throw new SQLException("Insert failed in " + getTableName());
			} else {
				_id = id;
				afterInsert(context, db);
				db.setTransactionSuccessful();
				retval = getUri();
			}
		} catch (SQLException e) {
			throw e;
		} finally {
			db.endTransaction();
		}

		if (retval != null) {
			notifyProviderOnChange(context);
		}
		return retval;
	}

	public synchronized int remove(final Context context,
			final SQLiteDatabase db) {
		final int result = db.delete(getTableName(), BaseColumns._ID + " IS ?",
				new String[] { Long.toString(_id) });

		if (result > 1) {
			notifyProviderOnChange(context);
		}

		return result;
	}

	public static void notifyProviderOnChange(final Context context,
			final Uri uri) {
		try {
			context.getContentResolver().notifyChange(uri, null, false);
		} catch (UnsupportedOperationException e) {
			// Catch this for test suite. Mock provider cant notify
		}
	}

	protected void notifyProviderOnChange(final Context context) {
		notifyProviderOnChange(context, getUri());
	}

	public void setId(final Uri uri) {
		_id = Long.parseLong(uri.getLastPathSegment());
	}

	protected void beforeInsert(final Context context, final SQLiteDatabase db) {

	}

	protected void afterInsert(final Context context, final SQLiteDatabase db) {

	}

	protected void beforeUpdate(final Context context, final SQLiteDatabase db) {

	}

	protected void afterUpdate(final Context context, final SQLiteDatabase db) {

	}

	protected void beforeRemove(final Context context, final SQLiteDatabase db) {

	}

	protected void afterRemove(final Context context, final SQLiteDatabase db) {

	}

	protected DAO(final Cursor c) {
	}

	protected DAO(final ContentValues values) {

	}

	protected DAO() {

	}

	public abstract ContentValues getContent();

	protected abstract String getTableName();
	
	public abstract String getContentType();
	
	/**
	 * Convenience method for normal operations. Updates "updated" field.
	 * Returns number of db-rows affected. Fail if < 1
	 */
	public abstract int save(final Context context);
	/**
	 * Delete object from database
	 */
	public int delete(final Context context) {
		return context.getContentResolver().delete(getUri(), null, null);
	}
}