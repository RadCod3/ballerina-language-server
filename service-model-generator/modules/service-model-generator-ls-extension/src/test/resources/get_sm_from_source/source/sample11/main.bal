import ballerinax/ibm.ibmmq;

listener ibmmq:Listener ibmListener = new (name = "QM1", host = "localhost", channel = "test_channel");

service ibmmq:Service on ibmListener {
    remote function onMessage(ibmmq:Message message, ibmmq:Caller caller) returns error? {
        do {
        } on fail error err {
            // handle error
            return error("unhandled error", err);
        }
    }

    remote function onError(ibmmq:Error ibmmqError) returns error? {
        do {
        } on fail error err {
            // handle error
            return error("unhandled error", err);
        }
    }
}
