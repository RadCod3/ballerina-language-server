type MyType record {|
    string name1;
    int i;
|};

type Types record {|
    MyType[][] t;
|};

public function main() {
    MyType[] myTypes1 = [{i: 0, name1: ""}, {i: 0, name1: ""}];
    MyType[] myTypes2 = from var x in myTypes1 select x;
    MyType[][] myTypes3 = [];
    Types types = {t: from var myType3 in myTypes3 select from var y in myType3 select y};
    MyType[][] myTypes4 = from var myType3 in myTypes3 select from var y in myType3 select y;
    MyType[][][] myTypes5 = [from var myType3 in myTypes3 select from var y in myType3 select y];
}
