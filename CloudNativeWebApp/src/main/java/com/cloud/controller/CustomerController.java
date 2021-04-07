package com.cloud.controller;

import com.cloud.service.*;
import com.timgroup.statsd.NonBlockingStatsDClient;
import com.timgroup.statsd.StatsDClient;
import cucumber.api.java.cs.A;
import org.apache.commons.validator.GenericValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.cloud.domain.*;

import java.sql.Timestamp;
import java.util.*;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;

import software.amazon.awssdk.auth.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.services.sqs.SQSClient;
import software.amazon.awssdk.services.sqs.model.*;

import com.cloud.config.pathVariableConfig;


import lombok.extern.slf4j.Slf4j;

import javax.servlet.http.HttpServletRequest;

import lombok.extern.slf4j.Slf4j;

import javax.servlet.http.HttpServletRequest;

@Slf4j
@RestController
public class CustomerController {
    @Autowired
    private StatsDClient statsDClient;
    @Autowired // This means to get the bean called userRepository
    // Which is auto-generated by Spring, we will use it to handle the data
    private UserRepository userRepository;
    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private UserService userService;
    @Autowired
    private BCryptPasswordEncoder bCryptPasswordEncoder;
    @Autowired
    private FileStorageService fileStorageService;


    private static final SecureRandom secureRandom = new SecureRandom(); //threadsafe
    private static final Base64.Encoder base64Encoder = Base64.getUrlEncoder(); //threadsafe

    @PostMapping(path="/v1/user",produces = "application/json") // Map ONLY POST Requests
    @ResponseStatus(value = HttpStatus.CREATED)
    public ResponseEntity createUser (@RequestBody userInfo info ) {
        long startTime = System.nanoTime();
        log.info("Create user called");
        statsDClient.incrementCounter("createUser");
        if(info.getPassword()==null||info.getEmail_address()==null||info.getFirst_name()==null||info.getLast_name()==null){
            //System.out.println("1");
            return new ResponseEntity<>(HttpStatus.valueOf(400));
        }
        if(info.getLast_name().equals("")||info.getFirst_name().equals("")){
            //System.out.println("2");
            return new ResponseEntity<>(HttpStatus.valueOf(400));
        }

        if(!userService.pwdValidation(info.getPassword())){
           // System.out.println("3");
            return new ResponseEntity<>(HttpStatus.valueOf(400));
        }
        if(!userService.emailVaildation(info.getEmail_address())){
           // System.out.println("4");
            return new ResponseEntity<>(HttpStatus.valueOf(400));
        }
        UserAccount n= new UserAccount();
        n.setFirst_name(info.getFirst_name());
        n.setLast_name(info.getLast_name());
        n.setPassword(info.getPassword());
        n.setEmailAddress(info.getEmail_address());
        n.setAccount_updated(new Timestamp(System.currentTimeMillis()).toString());
        n.setAccount_created(new Timestamp(System.currentTimeMillis()).toString());
        if(userService.CheckIfEmailExists(n.getEmailAddress())){
            return new ResponseEntity<>(HttpStatus.valueOf(400));
        }

        userService.saveWithEncoder(n);
        long endTime = System.nanoTime();
        long duration = (endTime - startTime);
        statsDClient.recordExecutionTime("CreateUser", duration / 1000000);
        return new ResponseEntity<>(new userInfo_noPwd(n.getId(),n.getFirst_name(),n.getLast_name(),n.getEmailAddress(),n.getAccount_created(),n.getAccount_updated()),HttpStatus.CREATED);
    }


    //Get User Info
    @GetMapping(path="/v1/user/self",produces = "application/json")
    public ResponseEntity getUserInfo (){
        long startTime = System.nanoTime();
        log.info("Get user called");
        statsDClient.incrementCounter("getUserInfo");
      Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
            if(authentication.getName().equals("anonymousUser")) return new ResponseEntity(HttpStatus.valueOf(401));
            System.out.println(authentication.getName());
            UserAccount n=userService.findByEmail(authentication.getName());
        long endTime = System.nanoTime();
        long duration = (endTime - startTime);
        statsDClient.recordExecutionTime("getUserInfo", duration / 1000000);
            return new ResponseEntity(new userInfo_noPwd(n.getId(),n.getFirst_name(),n.getLast_name(),n.getEmailAddress(),n.getAccount_created(),n.getAccount_updated()),HttpStatus.OK);



    }

    //Update user info
    @PutMapping(path="/v1/user/self",produces = "application/json")
    public ResponseEntity updateUserInfo (@RequestBody UserAccount_v2 n){
        long startTime = System.nanoTime();
        log.info("Update user called");
        statsDClient.incrementCounter("updateUserInfo");
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        //System.out.println(authentication.getName());
        if(authentication.getName().equals("anonymousUser")) return new ResponseEntity(HttpStatus.valueOf(401));
        UserAccount old=userService.findByEmail(authentication.getName());
        try {
            int i = 0;
            if (n.getFirst_name() != null && !"".equals(n.getFirst_name())) {
                old.setFirst_name(n.getFirst_name());
                i++;
            }
            if (n.getLast_name() != null && !"".equals(n.getLast_name())) {
                old.setLast_name(n.getLast_name());
                i++;
            }
            if (n.getPassword() != null && !"".equals(n.getPassword())) {
                if (!userService.pwdValidation(n.getPassword())) {
                    return new ResponseEntity(HttpStatus.valueOf(400));
                }
                //System.out.println("password changed");
                old.setPassword(bCryptPasswordEncoder.encode(n.getPassword()));
                i++;
            }
        }catch (Exception e){
            return new ResponseEntity(HttpStatus.BAD_REQUEST);
        }finally {
            long endTime = System.nanoTime();
            long duration = (endTime - startTime);
            statsDClient.recordExecutionTime("updateUserInfo", duration / 1000000);
        }
        old.setAccount_updated(new Timestamp(System.currentTimeMillis()).toString());
        userService.update(old);
        return new ResponseEntity(HttpStatus.valueOf(204));
    }

    @PostMapping(path="/v1/books",produces = "application/json") // Map ONLY POST Requests
    public ResponseEntity createBill (@RequestBody BookInfo info ) {
        long startTime = System.nanoTime();
        log.info("create Book called");
        statsDClient.incrementCounter("createBook");
            Authentication authentication =
                    SecurityContextHolder.getContext().getAuthentication();
            if (authentication.getName().equals("anonymousUser")) return new ResponseEntity(HttpStatus.valueOf(401));
//            if (QuestionInfoCheck(info)) return new ResponseEntity(HttpStatus.valueOf(400));

            UserAccount user = userService.findByEmail(authentication.getName());

            Book b = new Book();

//        CategoryInfo old;
//
//        for(CategoryInfo categoryInfo: info.getCategories()) {
//            try {
//                old = categoryRepository.findByCategory(categoryInfo.getCategory()).get();
//            } catch (Exception e) {
//                categoryRepository.save(categoryInfo);
//                b.getCategories().add(categoryInfo);
//            }
//        }
            if (info.getAuthor() == null || info.getIsbn() == null || info.getPublished_date() == null || info.getTitle() == null)
                return new ResponseEntity(HttpStatus.BAD_REQUEST);

            try {
                b.setTitle(info.getTitle());
                b.setAuthor(info.getAuthor());
                b.setIsbn(info.getIsbn());
                b.setPublished_date(info.getPublished_date());
                b.setBook_created(new Timestamp(System.currentTimeMillis()).toString());
                b.setUserId(user.getId());
                long startTime_1 = System.nanoTime();
                bookRepository.save(b);
                long endTime_1 = System.nanoTime();
                long duration_1 = (endTime_1 - startTime_1);
                statsDClient.recordExecutionTime("SaveBookQuery", duration_1 / 1000000);
            }catch (Exception e){
                return new ResponseEntity(HttpStatus.BAD_REQUEST);
            }finally {
                long endTime = System.nanoTime();
                long duration = (endTime - startTime);
                statsDClient.recordExecutionTime("createBill", duration / 1000000);
            }

        Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
        messageAttributes.put("Name", MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(user.getEmailAddress())
                .build());
        messageAttributes.put("Days", MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(""+"yyyy-MM-dd")
                .build());
        SQSClient sqsClient = SQSClient.builder()
                .credentialsProvider(InstanceProfileCredentialsProvider.builder().build())
                .region(pathVariableConfig.region)
                .build();
        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(pathVariableConfig.queueUrl)
                .messageBody("Create Book"+","+b.getId()+","+b.getTitle()+","+user.getLast_name()+" "+user.getFirst_name()+","+user.getEmailAddress())
                .delaySeconds(10)
                .build());

            return new ResponseEntity<>(b, HttpStatus.CREATED);


}

    @GetMapping(path="/v1/mybooks",produces = "application/json")
    public ResponseEntity getAllQuestions() {
        long startTime = System.nanoTime();
        log.info("getAllBooks called");
        statsDClient.incrementCounter("getAllBooks");
//        Authentication authentication =
//                SecurityContextHolder.getContext().getAuthentication();
//        System.out.println(authentication.getName());
//        if(authentication.getName().equals("anonymousUser")) return new ResponseEntity(HttpStatus.valueOf(401));
//        UserAccount user= userService.findByEmail(authentication.getName());
        long startTime_1 = System.nanoTime();
        Iterable<Book> questions= bookRepository.findAll();
        // This returns a JSON or XML with the users
        long endTime_1 = System.nanoTime();
        long duration_1 = (endTime_1 - startTime_1);
        statsDClient.recordExecutionTime("findAllBookByOwnerIdQuery", duration_1 / 1000000);
        return new ResponseEntity(questions, HttpStatus.valueOf(200));
    }

//    @GetMapping(path="/v1/user1/{id}",produces = "application/json")
//    public ResponseEntity getUser(@RequestParam String id) {
//        //Authentication authentication =
//        //        SecurityContextHolder.getContext().getAuthentication();
//        //System.out.println(authentication.getName());
//        //if(authentication.getName().equals("anonymousUser")) return new ResponseEntity(HttpStatus.valueOf(401));
//        //UserAccount user=userService.findByEmail(authentication.getName());
//
//        UserAccount n = userRepository.findById(id).get();
//        return new ResponseEntity(new userInfo_noPwd(n.getId(),n.getFirst_name(),n.getLast_name(),n.getEmailAddress(),n.getAccount_created(),n.getAccount_updated()),HttpStatus.OK);
//    }

    @GetMapping(path="/v1/books/{id}",produces = "application/json")
    public ResponseEntity getBook(@PathVariable String id) {
        long startTime = System.nanoTime();
        log.info("getBill called");
        statsDClient.incrementCounter("getBill");
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        //System.out.println(authentication.getName());
        if(authentication.getName().equals("anonymousUser")) return new ResponseEntity(HttpStatus.valueOf(401));
        UserAccount user=userService.findByEmail(authentication.getName());
        long startTime_1 = System.nanoTime();

            Optional<Book> book = bookRepository.findById(id);
            if(!book.get().getId().equals(id)){
                return new ResponseEntity(HttpStatus.NOT_FOUND);
            }
        long endTime_1 = System.nanoTime();
        long duration_1 = (endTime_1 - startTime_1);
        statsDClient.recordExecutionTime("findAllBillByIdQuery", duration_1 / 1000000);


        return new ResponseEntity(book,HttpStatus.OK);
    }

    @DeleteMapping(path="/v1/books/{id}",produces = "application/json")
    public ResponseEntity deleteQuestion(@PathVariable String id) {
        long startTime = System.nanoTime();
        log.info("delete Book called");
        statsDClient.incrementCounter("delete book");

        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        //System.out.println(authentication.getName());
        try{
        if(authentication.getName().equals("anonymousUser")) return new ResponseEntity(HttpStatus.valueOf(401));
        UserAccount user=userService.findByEmail(authentication.getName());
        Book book;
        try {
            book = bookRepository.findById(id).get();
            if(!book.getUserId().equals(user.getId())){
                return new ResponseEntity(HttpStatus.NOT_FOUND);
            }
        }
        catch (Exception e){
            return new ResponseEntity(HttpStatus.NOT_FOUND);
        }
        long startTime_1 = System.nanoTime();
        bookRepository.delete(book);
        long endTime_1 = System.nanoTime();
        long duration_1 = (endTime_1 - startTime_1);
        statsDClient.recordExecutionTime("deleteBookQuery", duration_1 / 1000000);

            Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
            messageAttributes.put("Name", MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue(user.getEmailAddress())
                    .build());
            messageAttributes.put("Days", MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue(""+"yyyy-MM-dd")
                    .build());
            SQSClient sqsClient = SQSClient.builder()
                    .credentialsProvider(InstanceProfileCredentialsProvider.builder().build())
                    .region(pathVariableConfig.region)
                    .build();
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(pathVariableConfig.queueUrl)
                    .messageBody("Create Book"+","+book.getId()+","+book.getTitle()+","+user.getLast_name()+" "+user.getFirst_name()+","+user.getEmailAddress())
                    .delaySeconds(10)
                    .build());

        return new ResponseEntity(HttpStatus.valueOf(204));
        }finally {
            long endTime = System.nanoTime();
            long duration = (endTime - startTime);
            statsDClient.recordExecutionTime("deleteBook", duration / 1000000);
        }

    }

    @PostMapping(path="/v1/books/{id}/image",produces = "application/json") // Map ONLY POST Requests
    private ResponseEntity attachImage(@RequestParam("image") MultipartFile file ,@PathVariable String id){
        long startTime = System.nanoTime();
        log.info("upload images called");
        statsDClient.incrementCounter("upload images call");
        try {
            Authentication authentication =
                    SecurityContextHolder.getContext().getAuthentication();
            //System.out.println(authentication.getName());
            if (authentication.getName().equals("anonymousUser")) return new ResponseEntity(HttpStatus.valueOf(401));
            UserAccount user = userService.findByEmail(authentication.getName());

            Book book = bookRepository.findById(id).get();

            if (!book.getUserId().equals(user.getId())) {
                System.out.println("You are not allowed to post image for others' book");
                return new ResponseEntity(HttpStatus.valueOf(401));
            }


//
//        if (book.getBook_images() != null) return new ResponseEntity(HttpStatus.BAD_REQUEST);

//        String contentType = file.getContentType();
//        if(!contentType.equals("application/pdf") && !contentType.equals("image/png") && !contentType.equals("image/jpg") && !contentType
//        .equals("image/jpeg")){
//            System.out.println("Content-type limited");
//            return new ResponseEntity(HttpStatus.BAD_REQUEST);
//        }

            long startTime_1 = System.nanoTime();
            FileInfo fileInfo = fileStorageService.storeFile(file, user.getId(), book.getId());
            List<FileInfo> list = book.getBook_images() == null ? new ArrayList<>() : book.getBook_images();
            list.add(fileInfo);
            bookRepository.save(book);
            long endTime_1 = System.nanoTime();
            long duration_1 = (endTime_1 - startTime_1);
            statsDClient.recordExecutionTime("Save books Query", duration_1 / 1000000);
            return new ResponseEntity(fileInfo, HttpStatus.valueOf(201));
        }finally {
            long endTime = System.nanoTime();
            long duration = (endTime - startTime);
            statsDClient.recordExecutionTime("upload image", duration / 1000000);
        }
    }

//    @GetMapping(path="/v1/question/{questionId}/answer/{answerId}",produces = "application/json")
//    private ResponseEntity getFile(@PathVariable String questionId, @PathVariable String answerId, HttpServletRequest request){
//        Authentication authentication =
//                SecurityContextHolder.getContext().getAuthentication();
//        //System.out.println(authentication.getName());
//        //if(authentication.getName().equals("anonymousUser")) return new ResponseEntity(HttpStatus.valueOf(401));
//        try{
//            Optional<AnswerInfo> answerInfos =answerRepository.findById(answerId);
//            return ResponseEntity.ok().body(answerInfos.get());
//        }catch (Exception e){
//            return new ResponseEntity(HttpStatus.valueOf(404));
//        }
//    }
//
    @DeleteMapping(path="/v1/books/{bookId}/image/{imageId}",produces = "application/json")
    private ResponseEntity deleteFile(@PathVariable String bookId, @PathVariable String imageId, HttpServletRequest request) {
        long startTime = System.nanoTime();
        log.info("attachFile called");
        statsDClient.incrementCounter("attachFile");
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        //System.out.println(authentication.getName());
        if (authentication.getName().equals("anonymousUser")) return new ResponseEntity(HttpStatus.valueOf(401));

        try {
            UserAccount user = userService.findByEmail(authentication.getName());
            Book book = bookRepository.findById(bookId).get();
            List<FileInfo> list = book.getBook_images();
            FileInfo info = fileInfoRepository.findById(imageId).get();

            if (!book.getUserId().equals(user.getId())) {
                return new ResponseEntity(HttpStatus.valueOf(401));
            }
            long startTime_1 = System.nanoTime();


//            fileStorageService.deleteFile(info, user.getId(), book.getId());
            fileStorageService.deleteFromS3(info);
            list.remove(info);
            book.setBook_images(list);
            bookRepository.save(book);
            fileInfoRepository.delete(info);
            long endTime_1 = System.nanoTime();
            long duration_1 = (endTime_1 - startTime_1);
            statsDClient.recordExecutionTime("SaveBillQuery", duration_1 / 1000000);
            System.out.println("answer in question deleted");
        } catch (Exception e) {
            return new ResponseEntity(HttpStatus.valueOf(400));
        } finally {
            long endTime = System.nanoTime();
            long duration = (endTime - startTime);
            statsDClient.recordExecutionTime("attachFile", duration / 1000000);
        }
        return new ResponseEntity(HttpStatus.NO_CONTENT);


    }
//
//
//    @PutMapping(path="/v1/question/{question_id}/answer/{answer_id}",produces = "application/json")
//    private ResponseEntity updateFile(@PathVariable String question_id, @PathVariable String answer_id, @RequestBody AnswerText answer_text, HttpServletRequest request){
//        Authentication authentication =
//                SecurityContextHolder.getContext().getAuthentication();
//        //System.out.println(authentication.getName());
//        if(authentication.getName().equals("anonymousUser")) return new ResponseEntity(HttpStatus.valueOf(401));
//
//        try{
//            UserAccount user=userService.findByEmail(authentication.getName());
//            Question question=questionRepository.findById(question_id).get();
//            if(!question.getUserId().equals(user.getId())){
//                return new ResponseEntity(HttpStatus.valueOf(404));
//            }
//            Optional<AnswerInfo> answerInfo = answerRepository.findById(answer_id);
//            answerInfo.get().setAnswer_text(answer_text.getAnswer_text());
//
//            List<AnswerInfo> old = question.getAnswers();
//            for (AnswerInfo info : old){
//                if (info.getId() == answer_id){
//                    info.setAnswer_text(answer_text.getAnswer_text());
//                }
//            }
//            question.setAnswers(old);
//            questionRepository.save(question);
//            answerRepository.save(answerInfo.get());
//
//            return new ResponseEntity(HttpStatus.NO_CONTENT);
//        }catch (Exception ex){
//            return new ResponseEntity(HttpStatus.valueOf(404));
//        }
//
//    }

}
