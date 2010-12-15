package edu.uci.ics.hyracks.storage.am.btree.tuples;

import java.nio.ByteBuffer;

import edu.uci.ics.hyracks.api.dataflow.value.ITypeTrait;
import edu.uci.ics.hyracks.storage.am.btree.api.IBTreeFrame;
import edu.uci.ics.hyracks.storage.am.btree.api.IBTreeTupleReference;

public class TypeAwareTupleReference implements IBTreeTupleReference {
	protected ByteBuffer buf;
	protected int fieldStartIndex;
	protected int fieldCount;	
	protected int tupleStartOff;
	protected int nullFlagsBytes;
	protected int dataStartOff;
	
	private ITypeTrait[] typeTraits;
	private VarLenIntEncoderDecoder encDec = new VarLenIntEncoderDecoder();
	private int[] decodedFieldSlots;
	
	public TypeAwareTupleReference(ITypeTrait[] typeTraits) {
		this.typeTraits = typeTraits;
		this.fieldStartIndex = 0;
	}
	
	@Override
	public void resetByOffset(ByteBuffer buf, int tupleStartOff) {
		this.buf = buf;
		this.tupleStartOff = tupleStartOff;
		
		// decode field slots
		int field = 0;
		int cumul = 0;
		int end = fieldStartIndex + fieldCount;
		encDec.reset(buf.array(), tupleStartOff + nullFlagsBytes);
		for(int i = fieldStartIndex; i < end; i++) {
			int staticDataLen = typeTraits[i].getStaticallyKnownDataLength();
			if(staticDataLen == ITypeTrait.VARIABLE_LENGTH) {
				cumul += encDec.decode();
				decodedFieldSlots[field++] = cumul;
			}
			else {
				cumul += staticDataLen;
				decodedFieldSlots[field++] = cumul;
			}
		}
		dataStartOff = encDec.getPos();
	}
	
	@Override
	public void resetByTupleIndex(IBTreeFrame frame, int tupleIndex) {		
		resetByOffset(frame.getBuffer(), frame.getTupleOffset(tupleIndex));		
	}
	
	@Override
	public void setFieldCount(int fieldCount) {
		this.fieldCount = fieldCount;
		if(decodedFieldSlots == null) {
			decodedFieldSlots = new int[fieldCount];
		}
		else {
			if(fieldCount > decodedFieldSlots.length) {
				decodedFieldSlots = new int[fieldCount];
			}
		}		
		nullFlagsBytes = getNullFlagsBytes();
		this.fieldStartIndex = 0;
	}
	
	@Override
	public void setFieldCount(int fieldStartIndex, int fieldCount) {
		setFieldCount(fieldCount);
		this.fieldStartIndex = fieldStartIndex;		
	}
	
	@Override
	public int getFieldCount() {
		return fieldCount;
	}

	@Override
	public byte[] getFieldData(int fIdx) {
		return buf.array();
	}

	@Override
	public int getFieldLength(int fIdx) {
		if(fIdx == 0) {
			return decodedFieldSlots[0];			
		}
		else {
			return decodedFieldSlots[fIdx] - decodedFieldSlots[fIdx-1];
		}
	}

	@Override
	public int getFieldStart(int fIdx) {				
		if(fIdx == 0) {			
			return dataStartOff;
		}
		else {			
			return dataStartOff + decodedFieldSlots[fIdx-1];
		}		
	}
	
	protected int getNullFlagsBytes() {
		return (int)Math.ceil(fieldCount / 8.0);
	}		
}