package io.nekohasekai.sagernet.fmt.naive;

import androidx.annotation.NonNull;
import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;
import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;
import org.jetbrains.annotations.NotNull;

public class NaiveBean extends AbstractBean {

    public static final Creator<NaiveBean> CREATOR = new CREATOR<NaiveBean>() {
        @NonNull
        @Override
        public NaiveBean newInstance() {
            return new NaiveBean();
        }

        @Override
        public NaiveBean[] newArray(int size) {
            return new NaiveBean[size];
        }
    };
    /**
     * Available proto: https, quic.
     */
    public String proto;
    public String username;
    public String password;
    public String extraHeaders;
    public String sni;
    public Integer insecureConcurrency;
    // sing-box socks
    public Boolean sUoT;

    @Override
    public void initializeDefaultValues() {
        if (serverPort == null) serverPort = 443;
        super.initializeDefaultValues();
        if (proto == null) proto = "https";
        if (username == null) username = "";
        if (password == null) password = "";
        if (extraHeaders == null) extraHeaders = "";
        if (sni == null) sni = "";
        if (insecureConcurrency == null) insecureConcurrency = 0;
        if (sUoT == null) sUoT = false;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(0);
        super.serialize(output);
        output.writeString(proto);
        output.writeString(username);
        output.writeString(password);
        // note: sequence is different from SagerNet,,,
        output.writeString(extraHeaders);
        output.writeString(sni);
        output.writeInt(insecureConcurrency);
        output.writeBoolean(sUoT);
    }

    @Override
    public boolean canTCPing() {
        return !proto.equals("quic");
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        proto = input.readString();
        username = input.readString();
        password = input.readString();
        extraHeaders = input.readString();
        sni = input.readString();
        insecureConcurrency = input.readInt();
        sUoT = input.readBoolean();
    }

    @NotNull
    @Override
    public NaiveBean clone() {
        return KryoConverters.deserialize(new NaiveBean(), KryoConverters.serialize(this));
    }
}
