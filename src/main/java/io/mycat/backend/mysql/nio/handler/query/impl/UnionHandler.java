package io.mycat.backend.mysql.nio.handler.query.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.log4j.Logger;

import io.mycat.backend.BackendConnection;
import io.mycat.backend.mysql.nio.handler.query.BaseDMLHandler;
import io.mycat.net.mysql.FieldPacket;
import io.mycat.net.mysql.RowDataPacket;
import io.mycat.plan.common.field.FieldUtil;
import io.mycat.plan.common.item.FieldTypes;
import io.mycat.plan.common.item.Item;
import io.mycat.server.NonBlockingSession;

/**
 * union all语句的handler，如果是union语句的话，则在handlerbuilder时，
 * 向unionallhandler后面添加distinctHandler
 * 
 * @author ActionTech
 * 
 */
public class UnionHandler extends BaseDMLHandler {
	private static final Logger logger = Logger.getLogger(UnionHandler.class);

	public UnionHandler(long id, NonBlockingSession session, List<Item> sels, int nodecount) {
		super(id, session);
		this.sels = sels;
		this.nodeCount = new AtomicInteger(nodecount);
		this.nodeCountField = new AtomicInteger(nodecount);
	}

	/**
	 * 因为union有可能是多个表，最终出去的节点仅按照第一个表的列名来
	 */
	private List<Item> sels;
	private AtomicInteger nodeCount;
	/* 供fieldeof使用的 */
	private AtomicInteger nodeCountField;
	private ReentrantLock lock = new ReentrantLock();
	private Condition conFieldSend = lock.newCondition();

	@Override
	public HandlerType type() {
		return HandlerType.UNION;
	}

	/**
	 * 所有的上一级表传递过来的信息全部视作Field类型
	 */
	public void fieldEofResponse(byte[] headernull, List<byte[]> fieldsnull, final List<FieldPacket> fieldPackets,
			byte[] eofnull, boolean isLeft, BackendConnection conn) {
		if (terminate.get())
			return;
		lock.lock();
		try {
			if (this.fieldPackets == null || this.fieldPackets.size() == 0) {
				this.fieldPackets = fieldPackets;
			} else {
				this.fieldPackets = unionFieldPackets(this.fieldPackets, fieldPackets);
			}
			if (nodeCountField.decrementAndGet() == 0) {
				// 将fieldpackets赋成正确的fieldname
				checkFieldPackets();
				nextHandler.fieldEofResponse(null, null, this.fieldPackets, null, this.isLeft, conn);
				conFieldSend.signalAll();
			} else {
				conFieldSend.await();
			}
		} catch (Exception e) {
			String msg = "Union field merge error, " + e.getLocalizedMessage();
			logger.warn(msg, e);
			conFieldSend.signalAll();
			session.onQueryError(msg.getBytes());
		} finally {
			lock.unlock();
		}
	}

	private void checkFieldPackets() {
		for (int i = 0; i < sels.size(); i++) {
			FieldPacket fp = this.fieldPackets.get(i);
			Item sel = sels.get(i);
			fp.name = sel.getItemName().getBytes();
			// @fix: union语句没有表名，只要列名相等即可
			fp.table = null;
		}
	}

	/**
	 * 将fieldpakcets和fieldpackets2进行merge，比如说
	 * 一个int的列和一个double的列union完了之后结果是一个double的列
	 * 
	 * @param fieldPackets
	 * @param fieldPackets2
	 */
	private List<FieldPacket> unionFieldPackets(List<FieldPacket> fieldPackets, List<FieldPacket> fieldPackets2) {
		List<FieldPacket> newFps = new ArrayList<FieldPacket>();
		for (int i = 0; i < fieldPackets.size(); i++) {
			FieldPacket fp1 = fieldPackets.get(i);
			FieldPacket fp2 = fieldPackets2.get(i);
			FieldPacket newFp = unionFieldPacket(fp1, fp2);
			newFps.add(newFp);
		}
		return newFps;
	}

	private FieldPacket unionFieldPacket(FieldPacket fp1, FieldPacket fp2) {
		FieldPacket union = new FieldPacket();
		union.catalog = fp1.catalog;
		union.charsetIndex = fp1.charsetIndex;
		union.db = fp1.db;
		union.decimals = (byte) Math.max(fp1.decimals, fp2.decimals);
		union.definition = fp1.definition;
		union.flags = fp1.flags | fp2.flags;
		union.length = Math.max(fp1.length, fp2.length);
		FieldTypes field_type1 = FieldTypes.valueOf(fp1.type);
		FieldTypes field_type2 = FieldTypes.valueOf(fp2.type);
		FieldTypes merge_field_type = FieldUtil.field_type_merge(field_type1, field_type2);
		union.type = merge_field_type.numberValue();
		return union;
	}

	/**
	 * 收到行数据包的响应处理，这里需要等上面的field都merge完了才可以发送
	 */
	public boolean rowResponse(byte[] rownull, final RowDataPacket rowPacket, boolean isLeft, BackendConnection conn) {
		if (terminate.get())
			return true;
		nextHandler.rowResponse(null, rowPacket, this.isLeft, conn);
		return false;
	}

	/**
	 * 收到行数据包结束的响应处理
	 */
	public void rowEofResponse(byte[] data, boolean isLeft, BackendConnection conn) {
		if (terminate.get())
			return;
		if (nodeCount.decrementAndGet() == 0) {
			nextHandler.rowEofResponse(data, this.isLeft, conn);
		}
	}

	@Override
	public void onTerminate() {
		lock.lock();
		try {
			this.conFieldSend.signalAll();
		} finally {
			lock.unlock();
		}
	}

}