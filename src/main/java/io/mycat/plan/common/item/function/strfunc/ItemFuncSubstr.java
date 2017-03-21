package io.mycat.plan.common.item.function.strfunc;

import java.util.List;

import io.mycat.plan.common.item.Item;

public class ItemFuncSubstr extends ItemStrFunc {

	public ItemFuncSubstr(List<Item> args) {
		super(args);
	}

	@Override
	public final String funcName() {
		return "SUBSTRING";
	}

	@Override
	public String valStr() {
		String str = args.get(0).valStr();
		long start = args.get(1).valInt().longValue();
		long length = args.size() == 3 ? args.get(2).valInt().longValue() : Long.MAX_VALUE;
		long tmp_length;
		if (this.nullValue = (args.get(0).isNull() || args.get(1).isNull()
				|| (args.size() == 3 && args.get(2).isNull())))
			return EMPTY;
		if (args.size() == 3 && length <= 0 && (length <= 0))
			return EMPTY;
		start = (start < 0) ? str.length() + start : start - 1;
		tmp_length = str.length() - start;
		length = Math.min(length, tmp_length);
		if (start == 0 && str.length() == length)
			return EMPTY;
		return str.substring((int) start, (int) (start + length));
	}
}