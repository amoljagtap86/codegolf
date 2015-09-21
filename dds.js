	(function(){
		var number = new Number(8675309);
		var sum = 0;
		var digitDifferences = [];
		getDigitDifference(number, digitDifferences);
		
		for(n in digitDifferences){
			sum +=	digitDifferences[n];	
		}

		console.log("sum: " + sum);

		function getDigitDifference(num, differences){
			differences.push(num);
			var newNumber ="";
			var numberStr = num.toString();	
			if(numberStr.length > 1){
				for (var i = 0; i < numberStr.length-1; i++) {
					var num1 = numberStr[i];
					var num2 = numberStr[i+1];
					var diff = Math.abs(num1-num2);
					newNumber += diff;
					};
				getDigitDifference(new Number(newNumber), differences);
				}
		};

})();