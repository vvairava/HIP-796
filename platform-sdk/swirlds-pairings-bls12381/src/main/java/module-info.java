import com.swirlds.pairings.bls12381.spi.Bls12381Provider;

module com.swirlds.pairings.bls12381 {
    requires org.apache.logging.log4j;
    requires resource.loader;
    requires com.sun.jna;
    requires com.swirlds.pairings.api;
    requires static com.github.spotbugs.annotations;

    provides com.swirlds.pairings.spi.BilinearPairingProvider with
            Bls12381Provider;
}
