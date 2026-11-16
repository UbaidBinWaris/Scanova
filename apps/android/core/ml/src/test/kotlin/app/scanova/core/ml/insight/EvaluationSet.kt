package app.scanova.core.ml.insight

/**
 * The offline evaluation set for document type and title: realistic text as
 * ML Kit returns it from phone scans (line by line, reading order, with its
 * typical mistakes — l for I, 0 for O, broken spacing), for the documents
 * people scan most. All names, numbers and addresses are invented.
 *
 * Each sample says the type a person would give it and the title a reviewer
 * would accept. Add a sample for every mistake found on a real document, then
 * change the rules; [DocumentInsightsEvaluationTest] reports the accuracy.
 */
internal data class Sample(
    val name: String,
    val type: DocumentType,
    /** The title a reviewer would accept; null when the text offers none. */
    val title: String?,
    val text: String,
)

internal val EVALUATION_SET: List<Sample> = listOf(
    // ── Receipts ────────────────────────────────────────────────────────
    Sample(
        "grocery receipt",
        DocumentType.RECEIPT,
        "Receipt – Imtiaz Store – 16 Nov 2026",
        """
        IMTIAZ STORE
        Shop 12, Main Boulevard Gulshan
        Karachi Tel: 021-34567890
        NTN: 1234567-8
        SALES RECEIPT
        Date: 16/11/2026 Time: 18:42
        Cashier: Ahmed Till: 03
        Item Qty Amount
        Milk Pack 1L 2 560.00
        Bread Large 1 180.00
        Eggs Dozen 1 420.00
        Sub Total 1,160.00
        GST 18% 208.80
        Total 1,368.80
        Cash 1,500.00
        Change 131.20
        Thank you for shopping with us!
        """,
    ),
    Sample(
        "restaurant receipt with FBR POS invoice number",
        DocumentType.RECEIPT,
        "Receipt – KFC – 14 Nov 2026",
        """
        KFC
        Gulberg III, Lahore
        FBR POS Invoice No: 123456789012345
        Dine In Table 7
        Order No: 4521
        2 Zinger Burger 1,580.00
        1 Fries Large 390.00
        2 Pepsi 345ml 300.00
        Sub Total 2,270.00
        Sales Tax 16% 363.20
        Total 2,633.20
        Card 2,633.20
        14-Nov-2026 21:15
        Thank you for visiting KFC
        """,
    ),
    Sample(
        "pharmacy cash memo with OCR noise",
        DocumentType.RECEIPT,
        "Receipt – Fazal Din Pharma Plus – 1 Nov 2026",
        """
        FAZAL DIN PHARMA PLUS
        Mall Road, Lahore
        Ph: 042-3571234O
        CASH MEMO
        Date 0l/11/2026
        Panadol Tab 500mg x 20 300.00
        Brufen Syp 120ml 185.OO
        T0TAL 485.00
        Cash Tendered 500.00
        Change Due 15.00
        """,
    ),
    Sample(
        "fuel station receipt",
        DocumentType.RECEIPT,
        "Receipt – PSO Service Station – 12 Nov 2026",
        """
        PSO SERVICE STATION
        Shahrah-e-Faisal Karachi
        RECEIPT NO: 88213
        DATE: 12-11-2026 TIME: 08:14
        PUMP: 04 NOZZLE: 2
        PRODUCT: PREMIUM
        LITRES: 20.00
        RATE: 272.50
        AMOUNT: 5,450.00
        PAYMENT: CASH
        THANK YOU COME AGAIN
        """,
    ),
    Sample(
        "coffee shop receipt, US date",
        DocumentType.RECEIPT,
        "Receipt – Gloria Jean's Coffees – 15 Nov 2026",
        """
        Gloria Jean's Coffees
        Dolmen Mall Clifton
        Receipt #10482
        Server: Sana Guests: 2
        1 Cappuccino Regular 650.00
        1 Blueberry Muffin 450.00
        Subtotal 1,100.00
        Service Charge 5% 55.00
        Total 1,155.00
        VISA **** 4821 1,155.00
        Nov 15, 2026 4:32 PM
        Thanks for your visit!
        """,
    ),
    Sample(
        "receipt with I read as l",
        DocumentType.RECEIPT,
        "Receipt – Imtiaz Store – 16 Nov 2026",
        """
        lMTlAZ STORE
        Receipt
        16.11.2026 10:05
        Sugar 1kg 160.00
        Rice 5kg 1,850.00
        Total 2,010.00
        Cash 2,100.00
        Change 90.00
        """,
    ),

    // ── Invoices ────────────────────────────────────────────────────────
    Sample(
        "software invoice",
        DocumentType.INVOICE,
        "Invoice – Acme Software Solutions (Pvt) Ltd – 3 Oct 2026",
        """
        INVOICE
        Acme Software Solutions (Pvt) Ltd
        45-B, Block 6, PECHS, Karachi
        Invoice No: INV-2026-0412
        Invoice Date: 03/10/2026
        Due Date: 02/11/2026
        Bill To:
        Hassan Traders
        12 Saddar Road, Peshawar
        Description Qty Unit Price Amount
        Website maintenance 1 45,000.00 45,000.00
        Hosting (12 months) 1 18,000.00 18,000.00
        Subtotal 63,000.00
        Sales Tax 16% 10,080.00
        Total Due 73,080.00
        Payment Terms: Net 30
        Bank: Meezan Bank IBAN: PK36MEZN0001234567890123
        """,
    ),
    Sample(
        "VAT tax invoice",
        DocumentType.INVOICE,
        "Invoice – Northwind Traders Ltd – 21 Oct 2026",
        """
        TAX INVOICE
        Northwind Traders Ltd
        VAT Reg No: GB 123 4567 89
        Invoice Number: 10023
        Date: 21 October 2026
        Billed To: Contoso Pvt Ltd
        Item Description Qty Rate Amount
        1 Office chairs 10 120.00 1,200.00
        2 Desks 5 250.00 1,250.00
        Net Amount 2,450.00
        VAT 20% 490.00
        Total 2,940.00
        Thank you for your business.
        Please remit payment within 30 days.
        """,
    ),
    Sample(
        "freelance invoice with OCR noise",
        DocumentType.INVOICE,
        "Invoice – Sara Malik Design Studio – 5 Nov 2026",
        """
        lnvoice #0042
        From: Sara Malik Design Studio
        Date: 2026-11-05
        lnvoice To: Bright Futures School
        Logo design 25,000
        Brochure (8 pages) 40,000
        Total Amount Due PKR 65,000
        Payment due within 15 days
        """,
    ),
    Sample(
        "proforma invoice",
        DocumentType.INVOICE,
        "Invoice – Al-Noor Textiles – 9 Nov 2026",
        """
        PROFORMA INVOICE
        Al-Noor Textiles
        Faisalabad, Pakistan
        Ref: PI/2026/118
        Date: 09-Nov-2026
        Buyer: Global Apparel LLC
        Cotton fabric 60" - 2,000 m @ USD 3.10 = USD 6,200.00
        Freight: USD 450.00
        Total: USD 6,650.00
        Validity: 30 days
        """,
    ),

    // ── Bills ───────────────────────────────────────────────────────────
    Sample(
        "electricity bill",
        DocumentType.BILL,
        "Bill – K-Electric – Oct 2026",
        """
        K-ELECTRIC
        ELECTRICITY BILL
        Account No: 0400012345678
        Consumer No: LA123456
        Billing Month: OCT 2026
        Issue Date: 05/11/2026
        Due Date: 20/11/2026
        Tariff: A-1(a) Residential
        Previous Reading 12450
        Present Reading 12780
        Units Consumed 330
        Energy Charges 9,240.00
        FPA 412.00
        TV Fee 35.00
        GST 1,645.00
        Amount Payable Within Due Date 11,332.00
        Amount Payable After Due Date 12,465.00
        """,
    ),
    Sample(
        "electricity bill in capitals, two-digit year",
        DocumentType.BILL,
        "Bill – Lahore Electric Supply Company – Nov 2026",
        """
        LAHORE ELECTRIC SUPPLY COMPANY
        LESCO
        BILL MONTH NOV 26
        REFERENCE NO 24 11223 3344556 U
        READING DATE 28-10-2026
        ISSUE DATE 01-11-2026
        DUE DATE 15-11-2026
        UNITS 215
        PAYABLE WITHIN DUE DATE 6,420
        LATE PAYMENT SURCHARGE 640
        """,
    ),
    Sample(
        "gas bill",
        DocumentType.BILL,
        "Bill – Sui Northern Gas Pipelines Limited – Oct 2026",
        """
        SUI NORTHERN GAS PIPELINES LIMITED
        Gas Bill
        Consumer ID: 98765432100
        Bill Date: 03 Nov 2026
        Billing Period: 01 Oct 2026 - 31 Oct 2026
        Gas Consumed (HM3): 1.25
        Gas Charges: 2,310
        Total Amount Payable: 2,310
        Due Date: 18 Nov 2026
        """,
    ),
    Sample(
        "telephone and broadband bill",
        DocumentType.BILL,
        "Bill – PTCL – Oct 2026",
        """
        PTCL
        Telephone & Broadband Bill
        Account ID: 0512345678
        Bill Month: October 2026
        Bill Date: 02/11/2026
        Previous Balance: 0.00
        Current Charges: 3,499.00
        Total Payable by Due Date: 3,499.00
        Due Date: 16/11/2026
        """,
    ),
    Sample(
        "water bill with OCR noise",
        DocumentType.BILL,
        "Bill – Karachi Water & Sewerage Corporation – Sep 2026",
        """
        KARACHI WATER & SEWERAGE CORPORATION
        WATER BlLL
        Consumer N0: 123-456-789
        Bill Month: Sep 2026
        Water Charges 1,200
        Sewerage Charges 600
        Arrears 0
        Payable Within Due Date 1,800
        """,
    ),

    // ── ID cards ────────────────────────────────────────────────────────
    Sample(
        "national identity card",
        DocumentType.ID_CARD,
        "ID card – Ayesha Khan",
        """
        PAKISTAN
        National Identity Card
        ISLAMIC REPUBLIC OF PAKISTAN
        Name
        Ayesha Khan
        Father Name
        Imran Khan
        Gender Country of Stay
        F Pakistan
        Identity Number Date of Birth
        35202-1234567-8 14.08.1995
        Date of Issue Date of Expiry
        10.01.2021 10.01.2031
        """,
    ),
    Sample(
        "driving licence",
        DocumentType.ID_CARD,
        "Driving licence – Muhammad Ali Raza",
        """
        GOVERNMENT OF PUNJAB
        DRIVING LICENCE
        Licence No: LHR-123456
        Name: MUHAMMAD ALI RAZA
        S/O: RAZA AHMED
        CNIC: 35201-7654321-9
        Date of Birth: 02-03-1990
        Valid Until: 02-03-2029
        Category: M/CYCLE, CAR
        """,
    ),
    Sample(
        "student card",
        DocumentType.ID_CARD,
        "ID card – Hamza Siddiqui",
        """
        UNIVERSITY OF THE PUNJAB
        STUDENT ID CARD
        Name: Hamza Siddiqui
        Roll No: BSCS-F23-045
        Department: Computer Science
        Valid Upto: 06/2027
        """,
    ),
    Sample(
        "passport data page",
        DocumentType.ID_CARD,
        "Passport – Ayesha Khan",
        """
        PASSPORT
        ISLAMIC REPUBLIC OF PAKISTAN
        Type Country Code Passport Number
        P PAK AB1234567
        Surname
        KHAN
        Given Names
        AYESHA
        Nationality
        PAKISTANI
        Date of Birth
        14 AUG 1995
        P<PAKKHAN<<AYESHA<<<<<<<<<<<<<<<<<<<<<<<<<<<
        AB12345670PAK9508149F3101105<<<<<<<<<<<<<<04
        """,
    ),
    Sample(
        "employee card",
        DocumentType.ID_CARD,
        "ID card – Bilal Ahmed",
        """
        TechNova Pvt Ltd
        EMPLOYEE ID CARD
        Name : Bilal Ahmed
        Designation: Software Engineer
        Employee ID: TN-0457
        Blood Group: B+
        If found please return to HR
        """,
    ),

    // ── Certificates ────────────────────────────────────────────────────
    Sample(
        "certificate of achievement, heading over two lines",
        DocumentType.CERTIFICATE,
        "Certificate of Achievement – Sara Ahmed",
        """
        CERTIFICATE
        OF ACHIEVEMENT
        This certificate is proudly presented to
        SARA AHMED
        for outstanding performance in the
        National Science Olympiad 2026
        Date: 12 November 2026
        Principal Director
        """,
    ),
    Sample(
        "course completion certificate",
        DocumentType.CERTIFICATE,
        "Certificate of Completion – Muhammad Usman",
        """
        Certificate of Completion
        This is to certify that
        Muhammad Usman
        has successfully completed the course
        Python for Data Analysis
        held from 01 September 2026 to 30 October 2026.
        Certificate No: DA-2026-1182
        """,
    ),
    Sample(
        "experience certificate written as a letter",
        DocumentType.CERTIFICATE,
        "Experience Certificate – Faisal Mehmood",
        """
        EXPERIENCE CERTIFICATE
        Date: 15/11/2026
        TO WHOM IT MAY CONCERN
        This is to certify that Mr. Faisal Mehmood has worked with
        Bright Systems (Pvt) Ltd as a Senior Accountant from
        01 March 2021 to 31 October 2026. During his employment
        we found him honest, hardworking and dedicated.
        We wish him success in his future endeavours.
        For Bright Systems (Pvt) Ltd
        HR Manager
        """,
    ),
    Sample(
        "participation certificate with OCR noise",
        DocumentType.CERTIFICATE,
        "Certificate of Participation – Zainab Fatima",
        """
        CERTlFlCATE OF PARTlClPATlON
        Awarded to
        Zainab Fatima
        in recognition of her participation in the
        Inter-School Debate Competition
        Lahore Grammar School, 2026
        """,
    ),
    Sample(
        "university degree",
        DocumentType.CERTIFICATE,
        "Bachelor of Science in Electrical Engineering – Ali Hassan",
        """
        UNIVERSITY OF ENGINEERING AND TECHNOLOGY
        LAHORE
        This is to certify that
        Ali Hassan
        having fulfilled the requirements has been conferred the degree of
        Bachelor of Science in Electrical Engineering
        in the year 2025
        Vice Chancellor Registrar
        """,
    ),

    // ── Letters ─────────────────────────────────────────────────────────
    Sample(
        "offer letter",
        DocumentType.LETTER,
        "Letter – Offer of Employment",
        """
        Bright Systems (Pvt) Ltd
        21-C, Gulberg II, Lahore
        Ref: BS/HR/2026/311
        10 November 2026
        Mr. Kamran Yousaf
        House 14, Street 9, Johar Town, Lahore
        Subject: Offer of Employment
        Dear Mr. Yousaf,
        We are pleased to offer you the position of Software Engineer at Bright Systems.
        Your monthly salary will be PKR 250,000 and your joining date will be 1 December 2026.
        Please sign and return a copy of this letter to confirm your acceptance.
        Yours sincerely,
        Nadia Hussain
        HR Manager
        """,
    ),
    Sample(
        "bank letter without a subject",
        DocumentType.LETTER,
        "Letter – Meezan Bank – 12 Nov 2026",
        """
        MEEZAN BANK
        Clifton Branch, Karachi
        12 November 2026
        To Whom It May Concern
        This is to confirm that Mr. Adeel Khan holds a current account with our branch
        since 2019 and the account is being maintained satisfactorily.
        This letter is issued on the request of the account holder without any
        responsibility on the part of the bank.
        Branch Manager
        """,
    ),
    Sample(
        "complaint letter",
        DocumentType.LETTER,
        "Letter – Complaint about incorrect billing",
        """
        15 November 2026
        The Manager
        Customer Services
        Jazz Telecom, Islamabad
        Subject: Complaint about incorrect billing
        Dear Sir/Madam,
        I am writing to complain about the amount charged on my postpaid connection
        for the month of October. I have been charged twice for the same package.
        I request you to review my account and refund the extra amount at the earliest.
        Yours faithfully,
        Usman Tariq
        """,
    ),
    Sample(
        "government letter in capitals",
        DocumentType.LETTER,
        "Letter – Renewal of NOC for Visit Abroad",
        """
        GOVERNMENT OF PAKISTAN
        MINISTRY OF INTERIOR
        No. F.12/2026-Admin
        Islamabad, the 3rd November, 2026
        SUBJECT: RENEWAL OF NOC FOR VISIT ABROAD
        Dear Sir,
        I am directed to refer to your application dated 20.10.2026 on the
        subject cited above and to say that the request has been acceded to.
        Yours faithfully,
        (Section Officer)
        """,
    ),
    Sample(
        "letter with a long Re: line",
        DocumentType.LETTER,
        "Letter – Audit of financial statements for the year",
        """
        Hassan & Co. Chartered Accountants
        14 Davis Road, Lahore
        November 8, 2026
        Mr. Bilal Sheikh
        Re: Audit of financial statements for the year ended June 30, 2026
        Dear Mr. Sheikh,
        Thank you for providing the records requested. We have completed our review
        and attach our report for your consideration. Please do not hesitate to contact us.
        Kind regards,
        Hassan & Co.
        """,
    ),

    // ── Forms ───────────────────────────────────────────────────────────
    Sample(
        "school admission form",
        DocumentType.FORM,
        "Admission Form",
        """
        THE CITY SCHOOL
        ADMISSION FORM
        (Please fill in BLOCK LETTERS)
        Student's Name: ____________________
        Father's Name: ____________________
        Date of Birth: ______________
        Class Applied For: ____________
        Address: ______________________________
        Contact No: _______________
        Signature of Parent/Guardian: __________
        For Office Use Only
        Admission No: ________
        """,
    ),
    Sample(
        "bank account opening form",
        DocumentType.FORM,
        "Account Opening Form - Individual",
        """
        HABIB BANK LIMITED
        ACCOUNT OPENING FORM - INDIVIDUAL
        Title of Account:
        Name of Applicant:
        CNIC No:
        Date of Birth:
        Mother's Maiden Name:
        Occupation:
        Monthly Income:
        Mailing Address:
        I hereby declare that the information provided above is true.
        Signature of Applicant
        """,
    ),
    Sample(
        "visa application form with OCR noise",
        DocumentType.FORM,
        "Visa Application Form",
        """
        VISA APPLlCATlON FORM
        Surname: __________ Given Names: __________
        Passport No: __________
        Nationality: __________
        Purpose of Visit: [ ] Tourism [ ] Business [ ] Study
        Intended Date of Arrival: __________
        Applicant's Signature: __________
        Photograph (passport size) to be attached
        """,
    ),
    Sample(
        "insurance claim form",
        DocumentType.FORM,
        "Health Insurance Claim Form",
        """
        EFU Life Assurance
        Health Insurance Claim Form
        Policy No:
        Employee Name:
        Patient Name:
        Relationship with Employee:
        Hospital Name:
        Date of Admission:
        Total Claim Amount:
        Declaration: I declare that the above information is correct.
        """,
    ),
    Sample(
        "event registration form with dotted lines",
        DocumentType.FORM,
        "Participant Registration Form",
        """
        Lahore Marathon 2026
        Participant Registration Form
        Full Name ......................................
        Age ............ Gender: Male / Female
        Phone ..........................
        Emergency Contact ..........................
        T-Shirt Size: S / M / L / XL
        I agree to the terms and conditions.
        """,
    ),

    // ── Bank statements ─────────────────────────────────────────────────
    Sample(
        "account statement",
        DocumentType.BANK_STATEMENT,
        "Bank statement – Habib Bank Limited – Oct 2026",
        """
        HABIB BANK LIMITED
        Account Statement
        Account Title: AHMED RAZA
        Account No: 1234-5678901-03
        IBAN: PK12HABB0012345678901203
        Statement Period: 01/10/2026 to 31/10/2026
        Date Description Debit Credit Balance
        01/10/2026 Opening Balance 85,420.50
        03/10/2026 ATM Withdrawal 10,000.00 75,420.50
        05/10/2026 Salary Credit 180,000.00 255,420.50
        12/10/2026 IBFT to Ali Khan 25,000.00 230,420.50
        28/10/2026 K-Electric Bill Payment 11,332.00 219,088.50
        31/10/2026 Closing Balance 219,088.50
        """,
    ),
    Sample(
        "e-statement with a from-to period",
        DocumentType.BANK_STATEMENT,
        "Bank statement – Meezan Bank – Sep 2026",
        """
        Meezan Bank
        e-Statement of Account
        Customer: Fatima Noor
        Branch: DHA Phase 5, Lahore
        From 01-Sep-2026 To 30-Sep-2026
        Txn Date Value Date Particulars Withdrawal Deposit Balance
        02-Sep-26 02-Sep-26 POS Purchase Daraz 4,599.00 60,112.00
        10-Sep-26 10-Sep-26 Funds Transfer 25,000.00 85,112.00
        21-Sep-26 21-Sep-26 Cheque 10045123 15,000.00 70,112.00
        Closing Balance 70,112.00
        """,
    ),
    Sample(
        "statement with OCR noise",
        DocumentType.BANK_STATEMENT,
        "Bank statement – United Bank Limited – Oct 2026",
        """
        UNITED BANK LIMITED
        STATEMENT OF ACCOUNT
        Period: 01.10.2026 - 31.10.2026
        Opening BaIance 12,450.00
        05.10.2026 Cash Deposit 20,000.00 32,450.00
        09.10.2026 ATM Cash Withdrawal 5,000.00 27,450.00
        15.10.2026 Utility Bill Payment 3,200.00 24,250.00
        20.10.2026 Online Transfer 10,000.00 14,250.00
        Closing BaIance 14,250.00
        """,
    ),
    Sample(
        "credit card statement",
        DocumentType.BANK_STATEMENT,
        "Bank statement – Standard Chartered – 25 Oct 2026",
        """
        Standard Chartered
        Credit Card Statement
        Statement Date: 25/10/2026
        Payment Due Date: 15/11/2026
        Card Number: XXXX XXXX XXXX 4821
        Previous Balance 45,200.00
        Payments Received 45,200.00
        New Purchases 38,640.00
        Minimum Amount Due 3,864.00
        Total Amount Due 38,640.00
        """,
    ),

    // ── Prescriptions ───────────────────────────────────────────────────
    Sample(
        "clinic prescription",
        DocumentType.PRESCRIPTION,
        "Prescription – Dr. Ayesha Siddiqui – 12 Nov 2026",
        """
        Dr. Ayesha Siddiqui
        MBBS, FCPS (Medicine)
        Consultant Physician
        City Care Clinic, Model Town, Lahore
        PMDC Reg No: 12345-P
        Patient: Hamid Ali Age: 45 Date: 12/11/2026
        Diagnosis: Viral fever
        Rx
        1. Tab Panadol 500mg 1+1+1 after meals x 5 days
        2. Syp Hydryllin 10ml 1+0+1 x 5 days
        3. Tab Ascard 75mg 0+0+1
        Follow up after one week
        """,
    ),
    Sample(
        "hospital prescription with OCR noise",
        DocumentType.PRESCRIPTION,
        "Prescription – Shaukat Khanum Memorial Hospital – 9 Nov 2026",
        """
        SHAUKAT KHANUM MEMORIAL HOSPITAL
        OUTPATIENT PRESCRIPTION
        MRN: 1234567
        Patient Name: Rubina Bibi
        Date: 09-11-2026
        Rx
        Cap Omeprazole 20 mg OD before breakfast x 14 days
        Tab Metformin 500 mg BD with food
        lnj Insulin as advised
        Next visit: 2 weeks
        """,
    ),
    Sample(
        "child specialist prescription",
        DocumentType.PRESCRIPTION,
        "Prescription – Dr. Imran Qureshi – 14 Nov 2026",
        """
        Dr. Imran Qureshi MBBS
        Child Specialist
        Date 14/11/2026
        Name: Baby Aliya Age 3y
        Syp Calpol 5ml TDS x 3 days
        Syp Brufen 5ml SOS
        Drops Nasal Saline
        """,
    ),
    Sample(
        "dental prescription",
        DocumentType.PRESCRIPTION,
        "Prescription – Dr. Sana Malik – 5 Nov 2026",
        """
        Smile Dental Care
        Dr. Sana Malik, BDS
        Date: 05 Nov 2026
        Patient: Omer Farooq
        Rx:
        Tab Augmentin 625mg BD x 5 days
        Tab Ponstan 250mg SOS
        Chlorhexidine mouthwash twice daily
        """,
    ),

    // ── Tickets ─────────────────────────────────────────────────────────
    Sample(
        "boarding pass",
        DocumentType.TICKET,
        "Ticket – Pakistan International Airlines – 20 Nov 2026",
        """
        PIA
        PAKISTAN INTERNATIONAL AIRLINES
        BOARDING PASS
        Passenger Name: KHAN/AYESHA MS
        Flight: PK 303 Date: 20NOV26
        From: KARACHI (KHI) To: ISLAMABAD (ISB)
        Gate: 22 Boarding Time: 06:45
        Seat: 14C Class: Economy
        Seq No: 087
        """,
    ),
    Sample(
        "train e-ticket",
        DocumentType.TICKET,
        "Ticket – Pakistan Railways – 22 Nov 2026",
        """
        PAKISTAN RAILWAYS
        E-TICKET
        Train: 1UP Karachi Express
        PNR: 4567891234
        Date of Journey: 22-11-2026
        Departure: 17:00 Karachi Cantt
        Arrival: 12:30 Lahore Jn
        Coach: 7 Berth: 23 Class: Economy
        Fare: Rs. 4,550
        """,
    ),
    Sample(
        "cinema ticket",
        DocumentType.TICKET,
        "Ticket – Cinepax – 16 Nov 2026",
        """
        CINEPAX
        Packages Mall Lahore
        ADMIT ONE
        Movie: The Last Voyage
        Screen 3 Row F Seat 12
        Show Date: 16/11/2026 Show Time: 19:30
        Ticket Price: Rs. 1,200
        Booking ID: CPX-889123
        """,
    ),
    Sample(
        "bus ticket with an ID number on it",
        DocumentType.TICKET,
        "Ticket – Daewoo Express – 18 Nov 2026",
        """
        DAEWOO EXPRESS
        Passenger Ticket
        Ticket No: DX-55621
        Passenger: Ali Raza CNIC: 35202-1112223-5
        Route: Lahore - Islamabad
        Departure: 18-11-2026 08:30
        Seat No: 21
        Fare: 3,100
        Have a safe journey
        """,
    ),
    Sample(
        "flight e-ticket receipt",
        DocumentType.TICKET,
        "Ticket – Emirates – 25 Nov 2026",
        """
        Emirates
        Electronic Ticket Receipt
        Booking Reference: X7K9P2
        Passenger: MR AHMED RAZA
        Ticket Number: 176 2412345678
        Date of Issue: 01 Nov 2026
        Flight EK 623 Lahore (LHE) to Dubai (DXB)
        Departure 25 Nov 2026 04:10 Arrival 25 Nov 2026 06:35
        Baggage: 30 kg Class: Economy
        """,
    ),

    // ── Anything else ───────────────────────────────────────────────────
    Sample(
        "rental agreement",
        DocumentType.OTHER,
        "Rental Agreement",
        """
        RENTAL AGREEMENT
        This agreement is made on 12 November 2026 between Mr. Asif Khan (Landlord)
        and Mr. Bilal Ahmed (Tenant) for the house situated at 45-A, DHA Phase 6, Lahore.
        The monthly rent shall be PKR 120,000 payable in advance.
        """,
    ),
    Sample(
        "meeting notes",
        DocumentType.OTHER,
        "Project Kickoff – Meeting Notes",
        """
        Project Kickoff – Meeting Notes
        Attendees: Sara, Bilal, Hamza
        1. Scope of the mobile app agreed
        2. First milestone due 30 November 2026
        3. Weekly sync every Monday 10:00
        """,
    ),
    Sample(
        "recipe",
        DocumentType.OTHER,
        "Chicken Karahi",
        """
        Chicken Karahi
        Ingredients
        1 kg chicken
        4 tomatoes
        2 tbsp ginger garlic paste
        Method
        Heat oil in a karahi and add chicken.
        """,
    ),
    Sample(
        "exam paper",
        DocumentType.OTHER,
        "University of Lahore",
        """
        University of Lahore
        Mid Term Examination - Fall 2026
        Course: Data Structures (CS-201)
        Time Allowed: 2 Hours Total Marks: 50
        Attempt all questions.
        Q1. Explain the difference between a stack and a queue.
        """,
    ),
    Sample(
        "news cutting that mentions a bill",
        DocumentType.OTHER,
        "Assembly passes finance bill",
        """
        Assembly passes finance bill
        ISLAMABAD: The National Assembly on Friday passed the finance bill for
        the fiscal year 2026-27 after a lengthy debate, with the opposition
        staging a walkout. The bill will now be sent to the Senate.
        """,
    ),
    Sample(
        "Urdu page read by the Latin model",
        DocumentType.OTHER,
        null,
        """
        Jl Lsl
        .., cJ' Lu
        4L>
        """,
    ),
).map { it.copy(text = it.text.trimIndent()) }
