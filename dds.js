	(function(){
		var number = new Number(8675309);
		var digitDifferences = [];
		getDigitDifference(number, digitDifferences);
		
		var sum = digitDifferences.reduce(function(initial, current){
			return initial + current; 
		}, 0);

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