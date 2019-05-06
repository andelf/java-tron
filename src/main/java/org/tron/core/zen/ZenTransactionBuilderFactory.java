package org.tron.core.zen;

import static org.tron.core.Wallet.tryDecodeFromBase58Check;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.utils.ByteArray;
import org.tron.core.Wallet;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.zen.address.ExpandedSpendingKey;
import org.tron.core.zen.address.PaymentAddress;
import org.tron.core.zen.merkle.IncrementalMerkleVoucherContainer;
import org.tron.core.zen.note.BaseNote.Note;
import org.tron.core.zen.note.NoteEntry;
import org.tron.core.zen.transaction.BaseOutPoint.OutPoint;
import org.tron.core.zen.transaction.Recipient;
import org.tron.core.zen.utils.KeyIo;
import org.tron.core.zen.zip32.ExtendedSpendingKey;
import org.tron.core.zen.zip32.HDSeed;

@Component
public class ZenTransactionBuilderFactory {

  private static long fee = 10_000_000L;

  private PaymentAddress shieldFromAddr;
  private ExtendedSpendingKey spendingKey;
  @Setter
  private String fromAddress;
  private List<Recipient> tOutputs;
  @Setter
  private List<Recipient> zOutputs;
  private List<NoteEntry> zSaplingInputs;

  private boolean isFromTAddress = false;
  private boolean isToTAddress = false;

  private long targetAmount = 10_000_000L;

  @Autowired
  private Wallet wallet;

  public ZenTransactionBuilderFactory() {
  }

  public ZenTransactionBuilderFactory(String fromAddr, List<Recipient> outputs) {
    init(fromAddr, outputs);
  }

  private void init(String fromAddr, List<Recipient> outputs) {

    this.fromAddress = fromAddr;
    byte[] tFromAddrBytes = tryDecodeFromBase58Check(fromAddr);

    if (tFromAddrBytes != null) {
      this.isFromTAddress = true;
    } else {
      this.shieldFromAddr = KeyIo.tryDecodePaymentAddress(fromAddr);
      if (shieldFromAddr == null) {
        throw new RuntimeException("unknown address type ");
      }
      if (!ZenWallet.getSpendingKeyForPaymentAddress(shieldFromAddr).isPresent()) {
        throw new RuntimeException(
            "From address does not belong to this wallet, spending key not found.");
      }
      this.spendingKey = ZenWallet.getSpendingKeyForPaymentAddress(shieldFromAddr).get();
      this.isFromTAddress = false;
    }

    if (outputs.size() < 1) {
      throw new RuntimeException("No recipients");
    }

    this.tOutputs = Lists.newArrayList();
    this.zOutputs = Lists.newArrayList();

    Set<String> allToAddress = Sets.newHashSet();
    long nTotalOut = 0;

    for (int i = 0; i < outputs.size(); i++) {
      Recipient recipient = outputs.get(i);
      if (allToAddress.contains(recipient.address)) {
        throw new RuntimeException("double address");
      }
      allToAddress.add(recipient.address);

      byte[] tToAddrBytes = tryDecodeFromBase58Check(recipient.address);
      if (tToAddrBytes != null) {
        tOutputs.add(recipient);
        isToTAddress = true;
      } else {
        PaymentAddress shieldToAddr = KeyIo.tryDecodePaymentAddress(recipient.address);
        if (shieldToAddr == null) {
          throw new RuntimeException("unknown address type.");
        }
        zOutputs.add(recipient);
        isToTAddress = false;
      }

      if (recipient.memo != null && !recipient.memo.equals("")) {
        throw new RuntimeException("Memo not supported yet.");
      }

      if (recipient.value < 0) {
        throw new RuntimeException("Invalid parameter, amount must be positive.");
      }
      nTotalOut += recipient.value;
    }

    targetAmount = nTotalOut + fee;

    if (this.tOutputs.size() != 0 && this.zOutputs.size() != 0) {
      throw new RuntimeException(
          "Transferring to two kind of addresses at the same time is not supported");
    }

    if (isFromTAddress && isToTAddress) {
      throw new RuntimeException("Transparent transfer is not supported");
    }
  }


  public TransactionCapsule build() {

    // todo：check value

    ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet);

    byte[] ovk = null;
    if (isFromTAddress) {
      // todo:get from input params
      long value = 0L;
      builder.setTransparentInput(fromAddress, value);
      HDSeed seed = KeyStore.seed;
      ovk = seed.ovkForShieldingFromTaddr();
    } else {
      boolean found = findUnspentNotes();
      if (!found) {
        throw new RuntimeException("Insufficient funds, no unspent notes found.");
      }

      // Get various necessary keys
      ExpandedSpendingKey expsk = spendingKey.getExpsk();
      ovk = expsk.fullViewingKey().getOvk();

      // Select Sapling notes
      List<OutPoint> ops = Lists.newArrayList();
      List<Note> notes = Lists.newArrayList();
      Long sum = 0L;
      for (NoteEntry t : zSaplingInputs) {
        ops.add(t.op);
        notes.add(t.note);
        sum += t.note.value;
        if (sum >= targetAmount) {
          break;
        }
      }

      // Fetch ShieldNote anchor and voucher
      byte[] anchor = null;
      List<Optional<IncrementalMerkleVoucherContainer>> vouchers = new ArrayList<>();

      ZenWallet.getNoteVouchers(ops, vouchers, anchor);

      // Add Sapling spends
      for (int i = 0; i < notes.size(); i++) {
        if (!vouchers.get(i).isPresent()) {
          throw new RuntimeException("Missing voucher for shield note");
        }
        builder.addSaplingSpend(expsk, notes.get(i), anchor, vouchers.get(i).get());
      }
    }

    if (isToTAddress) {
      for (Recipient out : tOutputs) {
        builder.setTransparentOutput(out.address, out.value);
      }
    } else {
      // Add Sapling outputs
      for (Recipient r : zOutputs) {
        String address = r.address;
        Long value = r.value;
        String hexMemo = r.memo;

        PaymentAddress addr = KeyIo.tryDecodePaymentAddress(address);
        if (addr == null) {
          throw new RuntimeException("");
        }

        byte[] memo = ByteArray.fromHexString(hexMemo);

        builder.addSaplingOutput(ovk, addr, value, memo);
      }
    }

    return builder.build();
  }

  private boolean findUnspentNotes() {

    List<NoteEntry> saplingEntries = ZenWallet.GetFilteredNotes(this.shieldFromAddr, true, true);

    if (saplingEntries.size() == 0) {
      return false;
    }

    for (NoteEntry entry : saplingEntries) {
      zSaplingInputs.add(entry);
    }

    // sort in descending order, so big notes appear first

    return true;
  }
}