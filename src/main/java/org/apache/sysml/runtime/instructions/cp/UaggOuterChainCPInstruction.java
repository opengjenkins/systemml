/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysml.runtime.instructions.cp;

import org.apache.sysml.lops.UAggOuterChain;
import org.apache.sysml.lops.PartialAggregate.CorrectionLocationType;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysml.runtime.functionobjects.ReduceAll;
import org.apache.sysml.runtime.functionobjects.ReduceCol;
import org.apache.sysml.runtime.instructions.InstructionUtils;
import org.apache.sysml.runtime.matrix.data.LibMatrixOuterAgg;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.runtime.matrix.operators.AggregateOperator;
import org.apache.sysml.runtime.matrix.operators.AggregateUnaryOperator;
import org.apache.sysml.runtime.matrix.operators.BinaryOperator;

public class UaggOuterChainCPInstruction extends UnaryCPInstruction
{
	//operators
	private AggregateUnaryOperator _uaggOp = null;
	private BinaryOperator _bOp = null;

	public UaggOuterChainCPInstruction(BinaryOperator bop, AggregateUnaryOperator uaggop, AggregateOperator aggop, CPOperand in1, CPOperand in2, CPOperand out, String opcode, String istr )
	{
		super(bop, in1, in2, out, opcode, istr);
		_cptype = CPINSTRUCTION_TYPE.UaggOuterChain;
		
		_uaggOp = uaggop;
		_bOp = bop;
			
		instString = istr;
	}

	public static UaggOuterChainCPInstruction parseInstruction(String str)
		throws DMLRuntimeException 
	{
		String parts[] = InstructionUtils.getInstructionPartsWithValueType(str);
		String opcode = parts[0];

		if ( opcode.equalsIgnoreCase(UAggOuterChain.OPCODE)) {
			AggregateUnaryOperator uaggop = InstructionUtils.parseBasicAggregateUnaryOperator(parts[1]);
			BinaryOperator bop = InstructionUtils.parseBinaryOperator(parts[2]);

			CPOperand in1 = new CPOperand(parts[3]);
			CPOperand in2 = new CPOperand(parts[4]);
			CPOperand out = new CPOperand(parts[5]);
					
			//derive aggregation operator from unary operator
			String aopcode = InstructionUtils.deriveAggregateOperatorOpcode(parts[1]);
			CorrectionLocationType corrLoc = InstructionUtils.deriveAggregateOperatorCorrectionLocation(parts[1]);
			String corrExists = (corrLoc != CorrectionLocationType.NONE) ? "true" : "false";
			AggregateOperator aop = InstructionUtils.parseAggregateOperator(aopcode, corrExists, corrLoc.toString());

			return new UaggOuterChainCPInstruction(bop, uaggop, aop, in1, in2, out, opcode, str);
		} 
		else {
			throw new DMLRuntimeException("UaggOuterChainCPInstruction.parseInstruction():: Unknown opcode " + opcode);
		}
	}
	
	
	@Override
	public void processInstruction(ExecutionContext ec) 
		throws DMLRuntimeException
	{	
		boolean rightCached = (_uaggOp.indexFn instanceof ReduceCol || _uaggOp.indexFn instanceof ReduceAll
				|| !LibMatrixOuterAgg.isSupportedUaggOp(_uaggOp, _bOp));

		MatrixBlock mbLeft = null, mbRight = null, mbOut = null;		
		//get the main data input
		if( rightCached ) { 
			mbLeft = ec.getMatrixInput(input1.getName());
			mbRight = ec.getMatrixInput(input2.getName());
		}
		else { 
			mbLeft = ec.getMatrixInput(input2.getName());
			mbRight = ec.getMatrixInput(input1.getName());
		}
		
		mbOut = mbLeft.uaggouterchainOperations(mbLeft, mbRight, mbOut, _bOp, _uaggOp);

		//release locks
		ec.releaseMatrixInput(input1.getName());
		ec.releaseMatrixInput(input2.getName());
		
		if( _uaggOp.aggOp.correctionExists )
			mbOut.dropLastRowsOrColums(_uaggOp.aggOp.correctionLocation);
		
		String output_name = output.getName();
		//final aggregation if required
		if(_uaggOp.indexFn instanceof ReduceAll ) //RC AGG (output is scalar)
		{
			//create and set output scalar
			ScalarObject ret = null;
			switch( output.getValueType() ) {
				case DOUBLE:  ret = new DoubleObject(output_name, mbOut.quickGetValue(0, 0)); break;
				
				default: 
					throw new DMLRuntimeException("Invalid output value type: "+output.getValueType());
			}
			ec.setScalarOutput(output_name, ret);
		}
		else //R/C AGG (output is rdd)
		{	
			//Additional memory requirement to convert from dense to sparse can be leveraged from released memory needed for input data above.
			mbOut.examSparsity();
			ec.setMatrixOutput(output_name, mbOut);
		}
		
	}		
}
